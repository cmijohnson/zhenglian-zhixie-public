package com.yingge.cloud.reminder.api;

import com.yingge.cloud.reminder.infrastructure.ReminderEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class ReminderDtos {
    private ReminderDtos() {}

    public record CreateRequest(@NotNull UUID customerId, @NotNull Instant remindAt,
                                @NotBlank @Size(max = 240) String title,
                                UUID sourceAnalysisVersionId) {}
    public record UpdateRequest(Instant remindAt, @Size(max = 240) String title, String status) {}
    public record Response(UUID id, UUID customerId, UUID interactionId, UUID assignedMembershipId,
                           Instant remindAt, String title, String status, String deliveryStatus, String reminderType,
                           UUID sourceAnalysisVersionId, boolean overdue,
                           Instant createdAt, Instant updatedAt, long version) {
        public static Response from(ReminderEntity row) {
            return new Response(row.getId(), row.getCustomerId(), row.getInteractionId(), row.getAssignedMembershipId(),
                    row.getRemindAt(), row.getTitle(), row.getStatus(), row.getDeliveryStatus(), row.getReminderType(),
                    row.getSourceAnalysisVersionId(),
                    "PENDING".equals(row.getStatus()) && row.getRemindAt().isBefore(Instant.now()),
                    row.getCreatedAt(), row.getUpdatedAt(), row.getVersion());
        }
    }
}
