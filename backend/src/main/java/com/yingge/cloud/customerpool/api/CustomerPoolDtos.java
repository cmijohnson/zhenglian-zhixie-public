package com.yingge.cloud.customerpool.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CustomerPoolDtos {
    private CustomerPoolDtos() {
    }

    public record MaskedTimeline(Instant occurredAt, String channel, String description) {}

    public record PoolEntry(
            UUID id, UUID customerId, String poolType, String releaseReason,
            String customerName, String school, String grade, String source, String demandSummary,
            List<MaskedTimeline> timeline, Instant availableAt, Instant priorityUntil,
            boolean canClaim, String status
    ) {}

    public record ClaimRequest(@NotBlank @Size(max = 120) String idempotencyKey) {}

    public record ClaimResult(
            UUID claimId, UUID poolEntryId, UUID customerId, String status,
            Instant claimedAt, Instant expiresAt, boolean idempotentReplay
    ) {}

    public record ReleaseRequest(
            @NotBlank @Size(max = 40) String reason,
            @Size(max = 1000) String note
    ) {}

    public record SecondLineRecruitment(
            UUID id, UUID customerId, String customerName, String school, String grade, String demandSummary,
            int requiredCount, int claimedCount, Instant volunteerUntil, String status
    ) {}

    public record SecondLineAssignment(
            UUID membershipId, String displayName, Instant startedAt, String assignmentSource
    ) {}

    public record SecondLineClaimResult(UUID recruitmentId, UUID customerId, int activeSecondLineCount,
                                        boolean recruitmentClosed) {}

    public record ContactAuthorizationRequest(@NotBlank @Size(max = 1000) String purpose) {}

    public record ContactAuthorizationDecision(
            @NotNull Boolean approved,
            @Size(max = 1000) String reason,
            Long expectedVersion
    ) {}

    public record ContactAuthorization(
            UUID id, UUID customerId, UUID requesterMembershipId, UUID ownerMembershipId,
            String purpose, String status, Instant ownerDeadline, UUID decidedByMembershipId,
            String decisionReason, Instant createdAt, Instant decidedAt, long version
    ) {}

    public record AssignSecondLineRequest(@NotNull UUID membershipId) {}

    public record ResolveTakeoverRequest(
            @NotNull UUID selectedMembershipId,
            @NotBlank @Size(max = 1000) String reason
    ) {}
}
