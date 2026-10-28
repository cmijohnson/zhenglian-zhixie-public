package com.yingge.cloud.task.api;

import com.yingge.cloud.task.domain.TaskAssignmentSource;
import com.yingge.cloud.task.domain.TaskAssignmentStatus;
import com.yingge.cloud.task.domain.TaskDistributionType;
import com.yingge.cloud.task.domain.WorkTaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class TaskDtos {
    private TaskDtos() {}

    public record CreateRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 6000) String description,
            @NotNull TaskDistributionType distributionType,
            @NotNull Instant startAt,
            @NotNull Instant dueAt,
            @Positive Integer maxClaimants,
            Set<UUID> assigneeIds
    ) {}

    public record MemberUpdateRequest(
            @NotNull TaskAssignmentStatus status,
            @Size(max = 5000) String resultSummary,
            @Size(max = 1000) String evidenceUrl
    ) {}

    public record ReviewRequest(
            @NotNull TaskAssignmentStatus status,
            @Size(max = 2000) String feedback
    ) {}

    public record AssignmentResponse(
            UUID id, UUID membershipId, String memberDisplayName, TaskAssignmentSource assignmentSource,
            TaskAssignmentStatus status, String resultSummary, String evidenceUrl, String reviewFeedback,
            Instant claimedAt, Instant submittedAt, Instant completedAt, Instant updatedAt, long version
    ) {}

    public record TaskResponse(
            UUID id, String title, String description, TaskDistributionType distributionType,
            Instant startAt, Instant dueAt, Integer maxClaimants, WorkTaskStatus status,
            long assignmentCount, long completedCount, boolean overdue, boolean claimable,
            AssignmentResponse myAssignment, Instant createdAt, long version
    ) {}

    public record MatrixRow(
            TaskResponse task,
            List<AssignmentResponse> assignments
    ) {}
}
