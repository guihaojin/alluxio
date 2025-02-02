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

package alluxio.job.wire;

import alluxio.exception.status.InvalidArgumentException;
import alluxio.grpc.JobType;
import alluxio.job.util.SerializationUtils;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.protobuf.ByteString;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The job descriptor.
 */
public final class PlanInfo implements JobInfo {
  private final long mId;
  private final String mName;
  private final String mDescription;
  private final String mErrorMessage;
  private final List<JobInfo> mChildren;
  private final Status mStatus;
  private final String mResult;
  private final long mLastUpdated;

  /**
   * JobInfo constructor exposed for testing.
   * @param id job id
   * @param name name of the job
   * @param status job status
   * @param lastUpdated last updated time in milliseconds
   * @param errorMessage job error message
   */
  public PlanInfo(long id, String name, Status status, long lastUpdated,
                  @Nullable String errorMessage) {
    mId = id;
    mName = name;
    mDescription = "";
    mStatus = status;
    mLastUpdated = lastUpdated;
    mErrorMessage = (errorMessage == null) ? "" : errorMessage;
    mChildren = ImmutableList.of();
    mResult = null;
  }

  /**
   * Constructs the plan info from the job master's internal representation of job info.
   *
   * @param planInfo the job master's internal job info
   */
  public PlanInfo(alluxio.job.plan.meta.PlanInfo planInfo) {
    mId = planInfo.getId();
    mName = planInfo.getJobConfig().getName();
    mDescription = planInfo.getJobConfig().toString();
    mErrorMessage = planInfo.getErrorMessage();
    mChildren = Lists.newArrayList();
    mStatus = Status.valueOf(planInfo.getStatus().name());
    mResult = planInfo.getResult();
    mLastUpdated = planInfo.getLastStatusChangeMs();
    for (TaskInfo taskInfo : planInfo.getTaskInfoList()) {
      mChildren.add(taskInfo);
    }
  }

  /**
   * Constructs a new instance of {@link PlanInfo} from a proto object.
   *
   * @param jobInfo the proto object
   * @throws IOException if the deserialization fails
   */
  public PlanInfo(alluxio.grpc.JobInfo jobInfo) throws IOException {
    Preconditions.checkArgument(jobInfo.getType().equals(JobType.PLAN), "Invalid type");

    mId = jobInfo.getId();
    mName = jobInfo.getName();
    mDescription = jobInfo.getDescription();
    mErrorMessage = jobInfo.getErrorMessage();
    mChildren = new ArrayList<>();
    for (alluxio.grpc.JobInfo taskInfo : jobInfo.getChildrenList()) {
      mChildren.add(new TaskInfo(taskInfo));
    }
    mStatus = Status.valueOf(jobInfo.getStatus().name());
    if (jobInfo.hasResult()) {
      try {
        mResult = SerializationUtils.deserialize(jobInfo.getResult().toByteArray()).toString();
      } catch (ClassNotFoundException e) {
        throw new InvalidArgumentException(e);
      }
    } else {
      mResult = null;
    }
    mLastUpdated = jobInfo.getLastUpdated();
  }

  @Override
  public Long getParentId() {
    return null;
  }

  @Override
  public long getId() {
    return mId;
  }

  @Override
  public String getName() {
    return mName;
  }

  @Override
  public String getDescription() {
    return mDescription;
  }

  @Override
  public String getResult() {
    return mResult;
  }

  @Override
  public Status getStatus() {
    return mStatus;
  }

  @Override
  public Collection<JobInfo> getChildren() {
    return mChildren;
  }

  @Override
  public String getErrorMessage() {
    return mErrorMessage;
  }

  @Override
  public long getLastUpdated() {
    return mLastUpdated;
  }

  @Override
  public alluxio.grpc.JobInfo toProto() throws IOException {
    List<alluxio.grpc.JobInfo> taskInfos = new ArrayList<>();
    for (JobInfo taskInfo : mChildren) {
      taskInfos.add(taskInfo.toProto());
    }
    alluxio.grpc.JobInfo.Builder jobInfoBuilder = alluxio.grpc.JobInfo.newBuilder().setId(mId)
        .setErrorMessage(mErrorMessage).addAllChildren(taskInfos).setStatus(mStatus.toProto())
        .setName(mName).setDescription(mDescription).setType(JobType.PLAN);
    if (mResult != null && !mResult.isEmpty()) {
      ByteBuffer result =
          mResult == null ? null : ByteBuffer.wrap(SerializationUtils.serialize(mResult));
      jobInfoBuilder.setResult(ByteString.copyFrom(result));
    }
    jobInfoBuilder.setLastUpdated(mLastUpdated);
    return jobInfoBuilder.build();
  }

  @Override
  public boolean equals(Object o) {
    if (o == null) {
      return false;
    }
    if (this == o) {
      return true;
    }
    if (!(o instanceof PlanInfo)) {
      return false;
    }
    PlanInfo that = (PlanInfo) o;
    return Objects.equal(mId, that.mId)
        && Objects.equal(mErrorMessage, that.mErrorMessage)
        && Objects.equal(mChildren, that.mChildren)
        && Objects.equal(mStatus, that.mStatus)
        && Objects.equal(mResult, that.mResult)
        && Objects.equal(mLastUpdated, that.mLastUpdated)
        && Objects.equal(mName, that.mName)
        && Objects.equal(mDescription, that.mDescription);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(mId, mErrorMessage, mChildren, mStatus, mResult, mLastUpdated,
        mName, mDescription);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("id", mId)
        .add("errorMessage", mErrorMessage)
        .add("childPlanInfoList", mChildren)
        .add("status", mStatus)
        .add("result", mResult)
        .add("lastUpdated", mLastUpdated)
        .add("name", mName)
        .add("description", mDescription)
        .toString();
  }
}
