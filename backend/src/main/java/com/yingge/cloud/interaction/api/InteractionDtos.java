package com.yingge.cloud.interaction.api;

import com.yingge.cloud.interaction.infrastructure.InteractionEntity;
import com.yingge.cloud.market.domain.InteractionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class InteractionDtos {
    private InteractionDtos() {
    }

    public record CreateRequest(
            Instant contactedAt,
            @NotBlank @Size(max = 80) String channel,
            @NotBlank @Size(max = 500) String summary,
            @Size(max = 10000) String details,
            @Size(max = 4000) String outcome,
            @Size(max = 4000) String verificationTodo,
            Instant nextFollowUpAt,
            String currentStatus,
            String intentionLevel,
            String verificationStatus,
            Set<UUID> tagIds,
            InteractionType interactionType,
            @Size(max = 240) String sourceMessageId,
            Boolean tagUpdateRequested
    ) {
        public CreateRequest(Instant contactedAt, String channel, String summary, String details,
                             String outcome, String verificationTodo, Instant nextFollowUpAt,
                             String currentStatus, String intentionLevel, String verificationStatus,
                             Set<UUID> tagIds) {
            this(contactedAt, channel, summary, details, outcome, verificationTodo, nextFollowUpAt,
                    currentStatus, intentionLevel, verificationStatus, tagIds, null, null, null);
        }

        public InteractionType effectiveType() {
            return interactionType == null ? InteractionType.INTERNAL_NOTE : interactionType;
        }

        /**
         * New clients explicitly opt into replacing ordinary customer tags. For rolling-upgrade
         * compatibility, a legacy non-empty tag list still means replace; an empty legacy list is
         * treated as no-op because Android 0.2.1 sent it for every timeline write.
         */
        public boolean shouldUpdateTags() {
            if (tagUpdateRequested != null) return tagUpdateRequested;
            return tagIds != null && !tagIds.isEmpty();
        }
    }

    public record Response(
            UUID id, UUID customerId, Instant contactedAt, String channel, String summary, String details,
            String outcome, String verificationTodo, Instant nextFollowUpAt,
            UUID createdByMembershipId, String createdByDisplayName, Instant createdAt,
            InteractionType interactionType, String deadlineEffect, String deadlineEffectReason
    ) {
        public static Response from(InteractionEntity row) {
            return from(row, row.getCreatedByDisplayName());
        }

        public static Response from(InteractionEntity row, String createdByDisplayName) {
            return new Response(row.getId(), row.getCustomerId(), row.getContactedAt(), row.getChannel(),
                    row.getSummary(), row.getDetails(), row.getOutcome(), row.getVerificationTodo(),
                    row.getNextFollowUpAt(), row.getCreatedByMembershipId(), createdByDisplayName, row.getCreatedAt(),
                    row.getInteractionType(), row.getDeadlineEffect(), row.getDeadlineEffectReason());
        }
    }
}
