package com.yingge.cloud.task.infrastructure;

import com.yingge.cloud.task.domain.TaskAssignmentSource;
import com.yingge.cloud.task.domain.TaskAssignmentStatus;
import com.yingge.cloud.shared.api.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_task_assignments")
public class WorkTaskAssignmentEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "task_id", nullable = false)
    private UUID taskId;
    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;
    @Enumerated(EnumType.STRING)
    @Column(name = "assignment_source", nullable = false)
    private TaskAssignmentSource assignmentSource;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskAssignmentStatus status;
    @Column(name = "result_summary")
    private String resultSummary;
    @Column(name = "evidence_url")
    private String evidenceUrl;
    @Column(name = "review_feedback")
    private String reviewFeedback;
    @Column(name = "claimed_at")
    private Instant claimedAt;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected WorkTaskAssignmentEntity() {}

    public WorkTaskAssignmentEntity(UUID organizationId, UUID taskId, UUID membershipId,
                                    TaskAssignmentSource source) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.taskId = taskId;
        this.membershipId = membershipId;
        this.assignmentSource = source;
        this.status = TaskAssignmentStatus.PENDING;
        this.claimedAt = source == TaskAssignmentSource.CLAIMED ? now : null;
        this.updatedAt = now;
    }

    public void updateByMember(TaskAssignmentStatus requestedStatus, String resultSummary, String evidenceUrl) {
        if (requestedStatus == TaskAssignmentStatus.IN_PROGRESS) {
            if (status == TaskAssignmentStatus.COMPLETED || status == TaskAssignmentStatus.SUBMITTED) {
                throw ApiException.conflict("TASK_STATUS_LOCKED", "当前任务状态不能改为进行中");
            }
            status = TaskAssignmentStatus.IN_PROGRESS;
        } else if (requestedStatus == TaskAssignmentStatus.SUBMITTED) {
            if (status == TaskAssignmentStatus.COMPLETED) {
                throw ApiException.conflict("TASK_ALREADY_COMPLETED", "任务已经验收完成");
            }
            if (resultSummary == null || resultSummary.isBlank()) {
                throw ApiException.badRequest("TASK_RESULT_REQUIRED", "提交任务时必须填写成果说明");
            }
            status = TaskAssignmentStatus.SUBMITTED;
            submittedAt = Instant.now();
        } else {
            throw ApiException.badRequest("INVALID_MEMBER_TASK_STATUS", "成员只能标记进行中或提交验收");
        }
        this.resultSummary = resultSummary;
        this.evidenceUrl = evidenceUrl;
        this.reviewFeedback = null;
        this.updatedAt = Instant.now();
    }

    public void review(TaskAssignmentStatus requestedStatus, String feedback) {
        if (status != TaskAssignmentStatus.SUBMITTED) {
            throw ApiException.conflict("TASK_NOT_SUBMITTED", "成员尚未提交该任务");
        }
        if (requestedStatus == TaskAssignmentStatus.COMPLETED) {
            status = TaskAssignmentStatus.COMPLETED;
            completedAt = Instant.now();
        } else if (requestedStatus == TaskAssignmentStatus.RETURNED) {
            if (feedback == null || feedback.isBlank()) {
                throw ApiException.badRequest("TASK_FEEDBACK_REQUIRED", "退回任务时必须填写原因");
            }
            status = TaskAssignmentStatus.RETURNED;
        } else {
            throw ApiException.badRequest("INVALID_REVIEW_TASK_STATUS", "验收结果只能是完成或退回");
        }
        this.reviewFeedback = feedback;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getTaskId() { return taskId; }
    public UUID getMembershipId() { return membershipId; }
    public TaskAssignmentSource getAssignmentSource() { return assignmentSource; }
    public TaskAssignmentStatus getStatus() { return status; }
    public String getResultSummary() { return resultSummary; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public String getReviewFeedback() { return reviewFeedback; }
    public Instant getClaimedAt() { return claimedAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
