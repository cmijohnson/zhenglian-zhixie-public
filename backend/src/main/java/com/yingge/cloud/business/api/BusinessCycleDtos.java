package com.yingge.cloud.business.api;

import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.business.domain.BusinessCycleType;
import com.yingge.cloud.business.domain.BusinessParticipantRole;
import com.yingge.cloud.business.infrastructure.BusinessCycleEntity;
import com.yingge.cloud.business.infrastructure.BusinessCycleEventEntity;
import com.yingge.cloud.business.infrastructure.BusinessCycleParticipantSnapshotEntity;
import com.yingge.cloud.customer.api.CustomerDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BusinessCycleDtos {
    private BusinessCycleDtos() {
    }

    public record CreateRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String summary,
            @NotBlank @Size(max = 128) String idempotencyKey
    ) {}

    public record DeclareWonRequest(
            @NotBlank @Size(max = 4000) String fact,
            @NotNull UUID suggestedPrimaryMembershipId,
            @Size(max = 2) List<UUID> suggestedAssistantMembershipIds,
            @NotNull Long expectedVersion
    ) {}

    public record LockParticipantsRequest(
            @NotNull UUID primaryMembershipId,
            @Size(max = 2) List<UUID> assistantMembershipIds,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record ConfirmQuoteRequest(
            @NotNull UUID primaryMembershipId,
            @Size(max = 2) List<UUID> assistantMembershipIds,
            @NotBlank @Size(max = 120) String quoteType,
            @NotNull Instant quotedAt,
            @Size(max = 1000) String emergencyExceptionReason,
            @NotNull Long expectedVersion
    ) {}

    public record RejectQuoteLockRequest(
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record ConfirmWonRequest(
            @NotNull UUID primaryMembershipId,
            @Size(max = 2) List<UUID> assistantMembershipIds,
            @Size(max = 1000) String emergencyExceptionReason,
            @NotNull Long expectedVersion
    ) {}

    public record ReviseParticipantsRequest(
            @NotNull UUID primaryMembershipId,
            @Size(max = 2) List<UUID> assistantMembershipIds,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record ParticipantChangeRequest(
            @NotNull UUID primaryMembershipId,
            @Size(max = 2) List<UUID> assistantMembershipIds,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record ParticipantChangeResponse(
            UUID id,
            UUID businessCycleId,
            UUID proposedPrimaryMembershipId,
            List<UUID> proposedAssistantMembershipIds,
            Set<UUID> affectedMembershipIds,
            Set<UUID> approvedMembershipIds,
            String reason,
            String status,
            long expectedCycleVersion,
            Instant createdAt,
            Instant appliedAt
    ) {}

    public record MarkLostRequest(
            @Size(max = 200) String competitorName,
            @Size(max = 4000) String fact,
            @NotNull Long expectedVersion
    ) {}

    public record CancelRequest(
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record CorrectStatusRequest(
            @NotNull BusinessCycleStatus targetStatus,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record CreateReferralRequest(
            @NotNull @Valid CustomerDtos.UpsertRequest customer,
            UUID sourceBusinessCycleId,
            @Size(max = 1000) String relationshipNote,
            @NotBlank @Size(max = 128) String idempotencyKey
    ) {}

    public record Participant(
            UUID membershipId,
            String displayName,
            BusinessParticipantRole role,
            int snapshotVersion,
            Instant confirmedAt
    ) {
        public static Participant from(BusinessCycleParticipantSnapshotEntity entity) {
            return new Participant(entity.getMembershipId(), entity.getDisplayNameSnapshot(), entity.getRoleType(),
                    entity.getSnapshotVersion(), entity.getConfirmedAt());
        }
    }

    public record Event(
            UUID id,
            UUID actorMembershipId,
            String action,
            BusinessCycleStatus previousStatus,
            BusinessCycleStatus newStatus,
            Map<String, Object> details,
            Instant createdAt
    ) {
        public static Event from(BusinessCycleEventEntity entity) {
            return new Event(entity.getId(), entity.getActorMembershipId(), entity.getAction(),
                    entity.getPreviousStatus(), entity.getNewStatus(), entity.getDetails(), entity.getCreatedAt());
        }
    }

    public record Response(
            UUID id,
            UUID customerId,
            String customerName,
            String customerQuality,
            int cycleNumber,
            BusinessCycleType cycleType,
            String title,
            String summary,
            BusinessCycleStatus status,
            boolean crossSale,
            int currentSnapshotVersion,
            UUID suggestedPrimaryMembershipId,
            List<UUID> suggestedAssistantMembershipIds,
            String lockRequestReason,
            UUID lockRequestedByMembershipId,
            Instant lockRequestedAt,
            String quoteType,
            Instant quotedAt,
            UUID quoteConfirmedByMembershipId,
            Instant participantLockedAt,
            String wonDeclarationFact,
            UUID wonDeclaredByMembershipId,
            Instant wonDeclaredAt,
            UUID wonConfirmedByMembershipId,
            Instant wonConfirmedAt,
            String emergencyExceptionReason,
            String competitorName,
            String competitorFact,
            Instant statusMarkedAt,
            UUID createdByMembershipId,
            Instant createdAt,
            Instant updatedAt,
            long version,
            List<Participant> participants,
            List<Event> events
    ) {
        public static Response from(BusinessCycleEntity entity, String customerName, String customerQuality,
                                    boolean crossSale,
                                    List<BusinessCycleParticipantSnapshotEntity> participants,
                                    List<BusinessCycleEventEntity> events) {
            return new Response(entity.getId(), entity.getCustomerId(), customerName, customerQuality,
                    entity.getCycleNumber(),
                    entity.getCycleType(), entity.getTitle(), entity.getSummary(), entity.getStatus(),
                    crossSale, entity.getCurrentSnapshotVersion(),
                    entity.getSuggestedPrimaryMembershipId(), entity.getSuggestedAssistantMembershipIds(),
                    entity.getLockRequestReason(), entity.getLockRequestedByMembershipId(),
                    entity.getLockRequestedAt(), entity.getQuoteType(), entity.getQuotedAt(),
                    entity.getQuoteConfirmedByMembershipId(), entity.getParticipantLockedAt(),
                    entity.getWonDeclarationFact(), entity.getWonDeclaredByMembershipId(), entity.getWonDeclaredAt(),
                    entity.getWonConfirmedByMembershipId(), entity.getWonConfirmedAt(),
                    entity.getEmergencyExceptionReason(), entity.getCompetitorName(), entity.getCompetitorFact(),
                    entity.getStatusMarkedAt(), entity.getCreatedByMembershipId(), entity.getCreatedAt(),
                    entity.getUpdatedAt(), entity.getVersion(), participants.stream().map(Participant::from).toList(),
                    events.stream().map(Event::from).toList());
        }
    }

    public record ReferralResponse(
            UUID referralId,
            UUID referrerCustomerId,
            UUID referredCustomerId,
            UUID sourceBusinessCycleId,
            String relationshipNote,
            Instant createdAt,
            CustomerDtos.Response customer
    ) {}
}
