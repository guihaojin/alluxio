/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.master.job;

import alluxio.Constants;
import alluxio.client.file.FileSystem;
import alluxio.client.file.FileSystemContext;
import alluxio.clock.SystemClock;
import alluxio.collections.IndexDefinition;
import alluxio.collections.IndexedSet;
import alluxio.conf.PropertyKey;
import alluxio.conf.ServerConfiguration;
import alluxio.exception.ExceptionMessage;
import alluxio.exception.JobDoesNotExistException;
import alluxio.exception.status.ResourceExhaustedException;
import alluxio.grpc.GrpcService;
import alluxio.grpc.JobCommand;
import alluxio.grpc.RegisterCommand;
import alluxio.grpc.ServiceType;
import alluxio.heartbeat.HeartbeatContext;
import alluxio.heartbeat.HeartbeatExecutor;
import alluxio.heartbeat.HeartbeatThread;
import alluxio.job.JobConfig;
import alluxio.job.JobServerContext;
import alluxio.job.MasterWorkerInfo;
import alluxio.job.meta.JobIdGenerator;
import alluxio.job.plan.PlanConfig;
import alluxio.job.wire.JobInfo;
import alluxio.job.wire.JobServiceSummary;
import alluxio.job.wire.TaskInfo;
import alluxio.master.AbstractMaster;
import alluxio.master.MasterContext;
import alluxio.master.job.command.CommandManager;
import alluxio.master.journal.NoopJournaled;
import alluxio.master.job.plan.PlanCoordinator;
import alluxio.master.job.plan.PlanTracker;
import alluxio.resource.LockResource;
import alluxio.underfs.UfsManager;
import alluxio.util.CommonUtils;
import alluxio.util.executor.ExecutorServiceFactories;
import alluxio.wire.WorkerInfo;
import alluxio.wire.WorkerNetAddress;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import io.grpc.Context;
import net.jcip.annotations.GuardedBy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.concurrent.ThreadSafe;

/**
 * The master that handles all job managing operations.
 */
@ThreadSafe
public final class JobMaster extends AbstractMaster implements NoopJournaled {
  private static final Logger LOG = LoggerFactory.getLogger(JobMaster.class);

  /**
   * The total number of jobs that the JobMaster may run at any moment.
   */
  private static final long JOB_CAPACITY =
      ServerConfiguration.getLong(PropertyKey.JOB_MASTER_JOB_CAPACITY);
  /**
   * The max number of jobs to purge when the master reaches maximum capacity.
   */
  private static final long MAX_PURGE_COUNT =
      ServerConfiguration.getLong(PropertyKey.JOB_MASTER_FINISHED_JOB_PURGE_COUNT);
  /**
   * The minimum amount of time to retain finished jobs.
   */
  private static final long RETENTION_MS =
      ServerConfiguration.getMs(PropertyKey.JOB_MASTER_FINISHED_JOB_RETENTION_TIME);

  // Worker metadata management.
  private final IndexDefinition<MasterWorkerInfo, Long> mIdIndex =
      new IndexDefinition<MasterWorkerInfo, Long>(true) {
        @Override
        public Long getFieldValue(MasterWorkerInfo o) {
          return o.getId();
        }
      };

  private final IndexDefinition<MasterWorkerInfo, WorkerNetAddress> mAddressIndex =
      new IndexDefinition<MasterWorkerInfo, WorkerNetAddress>(true) {
        @Override
        public WorkerNetAddress getFieldValue(MasterWorkerInfo o) {
          return o.getWorkerAddress();
        }
      };

  /**
   * The Filesystem context that the job master uses for its client.
   */
  private final JobServerContext mJobServerContext;

  /**
   * All worker information. Access must be controlled on mWorkers using the RW lock(mWorkerRWLock).
   */
  @GuardedBy("mWorkerRWLock")
  private final IndexedSet<MasterWorkerInfo> mWorkers = new IndexedSet<>(mIdIndex, mAddressIndex);

  /**
   * An RW lock that is used to control access to mWorkers.
   */
  private final ReentrantReadWriteLock mWorkerRWLock = new ReentrantReadWriteLock(true);

  /**
   * The next worker id to use.
   */
  private final AtomicLong mNextWorkerId = new AtomicLong(CommonUtils.getCurrentMs());

  /**
   * Manager for worker tasks.
   */
  private final CommandManager mCommandManager;

  /**
   * Manager for adding and removing plans.
   */
  private final PlanTracker mPlanTracker;

  /**
   * The job id generator.
   */
  private final JobIdGenerator mJobIdGenerator;

  /**
   * Creates a new instance of {@link JobMaster}.
   *
   * @param masterContext the context for Alluxio master
   * @param filesystem    the Alluxio filesystem client the job master uses to communicate
   * @param fsContext     the filesystem client's underlying context
   * @param ufsManager    the ufs manager
   */
  public JobMaster(MasterContext masterContext, FileSystem filesystem,
      FileSystemContext fsContext, UfsManager ufsManager) {
    super(masterContext, new SystemClock(),
        ExecutorServiceFactories.cachedThreadPool(Constants.JOB_MASTER_NAME));
    mJobServerContext = new JobServerContext(filesystem, fsContext, ufsManager);
    mCommandManager = new CommandManager();
    mJobIdGenerator = new JobIdGenerator();
    mPlanTracker = new PlanTracker(JOB_CAPACITY, RETENTION_MS, MAX_PURGE_COUNT);
  }

  /**
   * @return new job id
   */
  public long getNewJobId() {
    return mJobIdGenerator.getNewJobId();
  }

  @Override
  public void start(Boolean isLeader) throws IOException {
    super.start(isLeader);
    // Fail any jobs that were still running when the last job master stopped.
    for (PlanCoordinator planCoordinator : mPlanTracker.coordinators()) {
      if (!planCoordinator.isJobFinished()) {
        planCoordinator.setJobAsFailed("Job failed: Job master shut down during execution");
      }
    }
    if (isLeader) {
      getExecutorService()
          .submit(new HeartbeatThread(HeartbeatContext.JOB_MASTER_LOST_WORKER_DETECTION,
              new LostWorkerDetectionHeartbeatExecutor(),
              (int) ServerConfiguration.getMs(PropertyKey.JOB_MASTER_LOST_WORKER_INTERVAL),
              ServerConfiguration.global(), mMasterContext.getUserState()));
    }
  }

  @Override
  public Map<ServiceType, GrpcService> getServices() {
    Map<ServiceType, GrpcService> services = Maps.newHashMap();
    services.put(ServiceType.JOB_MASTER_CLIENT_SERVICE,
        new GrpcService(new JobMasterClientServiceHandler(this)));
    services.put(ServiceType.JOB_MASTER_WORKER_SERVICE,
        new GrpcService(new JobMasterWorkerServiceHandler(this)));
    return services;
  }

  @Override
  public String getName() {
    return Constants.JOB_MASTER_NAME;
  }

  /**
   * Runs a job with the given configuration.
   *
   * @param jobConfig the job configuration
   * @return the job id tracking the progress
   * @throws JobDoesNotExistException   when the job doesn't exist
   * @throws ResourceExhaustedException if the job master is too busy to run the job
   */
  public synchronized long run(JobConfig jobConfig)
      throws JobDoesNotExistException, ResourceExhaustedException {
    // This RPC service implementation triggers another RPC.
    // Run the implementation under forked context to avoid interference.
    // Then restore the current context at the end.
    Context forkedCtx = Context.current().fork();
    Context prevCtx = forkedCtx.attach();
    try {
      long jobId = getNewJobId();
      if (jobConfig instanceof PlanConfig) {
        mPlanTracker.run((PlanConfig) jobConfig, mCommandManager, mJobServerContext,
            getWorkerInfoList(), jobId);
        return jobId;
      }
      throw new JobDoesNotExistException(
          ExceptionMessage.JOB_DEFINITION_DOES_NOT_EXIST.getMessage(jobConfig.getName()));
    } finally {
      forkedCtx.detach(prevCtx);
    }
  }

  /**
   * Cancels a job.
   *
   * @param jobId the id of the job
   * @throws JobDoesNotExistException when the job does not exist
   */
  public void cancel(long jobId) throws JobDoesNotExistException {
    PlanCoordinator planCoordinator = mPlanTracker.getCoordinator(jobId);
    if (planCoordinator == null) {
      throw new JobDoesNotExistException(ExceptionMessage.JOB_DOES_NOT_EXIST.getMessage(jobId));
    }
    planCoordinator.cancel();
  }

  /**
   * @return list all the job ids
   */
  public List<Long> list() {
    return Lists.newArrayList(mPlanTracker.jobs());
  }

  /**
   * Gets information of the given job id.
   *
   * @param jobId the id of the job
   * @return the job information
   * @throws JobDoesNotExistException if the job does not exist
   */
  public JobInfo getStatus(long jobId) throws JobDoesNotExistException {
    PlanCoordinator planCoordinator = mPlanTracker.getCoordinator(jobId);
    if (planCoordinator == null) {
      throw new JobDoesNotExistException(ExceptionMessage.JOB_DOES_NOT_EXIST.getMessage(jobId));
    }
    return planCoordinator.getPlanInfoWire();
  }

  /**
   * Gets summary of the job service.
   *
   * @return {@link JobServiceSummary}
   */
  public alluxio.job.wire.JobServiceSummary getSummary() {
    Collection<PlanCoordinator> coordinators = mPlanTracker.coordinators();

    List<JobInfo> jobInfos = new ArrayList<>();

    for (PlanCoordinator coordinator : coordinators) {
      jobInfos.add(coordinator.getPlanInfoWire());
    }

    return new JobServiceSummary(jobInfos);
  }

  /**
   * Returns a worker id for the given worker.
   *
   * @param workerNetAddress the worker {@link WorkerNetAddress}
   * @return the worker id for this worker
   */
  public long registerWorker(WorkerNetAddress workerNetAddress) {
    // Run under exclusive lock for mWorkers
    try (LockResource workersLockExclusive = new LockResource(mWorkerRWLock.writeLock())) {
      // Check if worker has already been registered with this job master
      if (mWorkers.contains(mAddressIndex, workerNetAddress)) {
        // If the worker is trying to re-register, it must have died and been restarted. We need to
        // clean up the dead worker.
        LOG.info(
            "Worker at address {} is re-registering. Failing tasks for previous worker at that "
                + "address",
            workerNetAddress);
        MasterWorkerInfo deadWorker = mWorkers.getFirstByField(mAddressIndex, workerNetAddress);
        for (PlanCoordinator planCoordinator : mPlanTracker.coordinators()) {
          planCoordinator.failTasksForWorker(deadWorker.getId());
        }
        mWorkers.remove(deadWorker);
      }
      // Generate a new worker id.
      long workerId = mNextWorkerId.getAndIncrement();
      mWorkers.add(new MasterWorkerInfo(workerId, workerNetAddress));
      LOG.info("registerWorker(): WorkerNetAddress: {} id: {}", workerNetAddress, workerId);
      return workerId;
    }
  }

  /**
   * @return a list of {@link WorkerInfo} objects representing the workers in Alluxio
   */
  public List<WorkerInfo> getWorkerInfoList() {
    List<WorkerInfo> workerInfoList = new ArrayList<>(mWorkers.size());
    // Run under shared lock for mWorkers
    try (LockResource workersLockShared = new LockResource(mWorkerRWLock.readLock())) {
      for (MasterWorkerInfo masterWorkerInfo : mWorkers) {
        workerInfoList.add(masterWorkerInfo.generateClientWorkerInfo());
      }
    }
    return workerInfoList;
  }

  /**
   * Updates the tasks' status when a worker periodically heartbeats with the master, and sends the
   * commands for the worker to execute.
   *
   * @param workerId the worker id
   * @param taskInfoList the list of the task information
   * @return the list of {@link JobCommand} to the worker
   */
  public List<JobCommand> workerHeartbeat(long workerId, List<TaskInfo> taskInfoList) {
    String hostname;
    // Run under shared lock for mWorkers
    try (LockResource workersLockShared = new LockResource(mWorkerRWLock.readLock())) {
      MasterWorkerInfo worker = mWorkers.getFirstByField(mIdIndex, workerId);
      if (worker == null) {
        return Collections.singletonList(JobCommand.newBuilder()
            .setRegisterCommand(RegisterCommand.getDefaultInstance()).build());
      }
      hostname = worker.getWorkerAddress().getHost();
      // Update last-update-time of this particular worker under lock
      // to prevent lost worker detector clearing it under race
      worker.updateLastUpdatedTimeMs();
    }

    // Update task infos for all jobs involved
    Map<Long, List<TaskInfo>> taskInfosPerJob = new HashMap<>();
    for (TaskInfo taskInfo : taskInfoList) {
      taskInfo.setWorkerHost(hostname);
      if (!taskInfosPerJob.containsKey(taskInfo.getJobId())) {
        taskInfosPerJob.put(taskInfo.getJobId(), new ArrayList());
      }
      taskInfosPerJob.get(taskInfo.getJobId()).add(taskInfo);
    }
    for (Map.Entry<Long, List<TaskInfo>> taskInfosPair : taskInfosPerJob.entrySet()) {
      PlanCoordinator planCoordinator = mPlanTracker.getCoordinator(taskInfosPair.getKey());
      if (planCoordinator != null) {
        planCoordinator.updateTasks(taskInfosPair.getValue());
      }
    }
    return mCommandManager.pollAllPendingCommands(workerId);
  }

  /**
   * Lost worker periodic check.
   */
  private final class LostWorkerDetectionHeartbeatExecutor implements HeartbeatExecutor {

    /**
     * Constructs a new {@link LostWorkerDetectionHeartbeatExecutor}.
     */
    public LostWorkerDetectionHeartbeatExecutor() {}

    @Override
    public void heartbeat() {
      int masterWorkerTimeoutMs = (int) ServerConfiguration
          .getMs(PropertyKey.JOB_MASTER_WORKER_TIMEOUT);
      List<MasterWorkerInfo> lostWorkers = new ArrayList<MasterWorkerInfo>();
      // Run under shared lock for mWorkers
      try (LockResource workersLockShared = new LockResource(mWorkerRWLock.readLock())) {
        for (MasterWorkerInfo worker : mWorkers) {
          final long lastUpdate = mClock.millis() - worker.getLastUpdatedTimeMs();
          if (lastUpdate > masterWorkerTimeoutMs) {
            LOG.warn("The worker {} timed out after {}ms without a heartbeat!", worker, lastUpdate);
            lostWorkers.add(worker);
            for (PlanCoordinator planCoordinator : mPlanTracker.coordinators()) {
              planCoordinator.failTasksForWorker(worker.getId());
            }
          }
        }
      }
      // Remove lost workers
      if (!lostWorkers.isEmpty()) {
        // Run under exclusive lock for mWorkers
        try (LockResource workersLockExclusive = new LockResource(mWorkerRWLock.writeLock())) {
          for (MasterWorkerInfo lostWorker : lostWorkers) {
            // Check last update time for lost workers again as it could have been changed while
            // waiting for exclusive lock.
            final long lastUpdate = mClock.millis() - lostWorker.getLastUpdatedTimeMs();
            if (lastUpdate > masterWorkerTimeoutMs) {
              mWorkers.remove(lostWorker);
            }
          }
        }
      }
    }

    @Override
    public void close() {
      // Nothing to clean up
    }
  }
}
