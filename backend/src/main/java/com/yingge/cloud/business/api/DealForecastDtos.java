package com.yingge.cloud.business.api;

import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.business.domain.DealForecastEvidenceType;
import com.yingge.cloud.business.domain.DealForecastOutcome;
import com.yingge.cloud.business.domain.DealForecastStatus;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class DealForecastDtos {
    private DealForecastDtos() {}

    public record CreateRequest(
            @NotEmpty @Size(max = 4) List<@NotNull DealForecastEvidenceType> evidenceTypes,
            @Size(max = 1000) String otherEvidenceDescription,
            @NotEmpty @Size(max = 20) List<@NotNull UUID> evidenceInteractionIds,
            @NotBlank @Size(max = 4000) String factSummary,
            @NotNull @FutureOrPresent LocalDate expectedCloseDate,
            @Size(max = 4000) String currentObstacle,
            @NotBlank @Size(max = 4000) String nextAction,
            @NotNull Instant nextActionAt,
            @NotBlank @Size(max = 128) String idempotencyKey
    ) {}

    public record UpdateRequest(
            @NotEmpty @Size(max = 4) List<@NotNull DealForecastEvidenceType> evidenceTypes,
            @Size(max = 1000) String otherEvidenceDescription,
            @NotEmpty @Size(max = 20) List<@NotNull UUID> evidenceInteractionIds,
            @NotBlank @Size(max = 4000) String factSummary,
            @NotNull @FutureOrPresent LocalDate expectedCloseDate,
            @Size(max = 4000) String currentObstacle,
            @NotBlank @Size(max = 4000) String nextAction,
            @NotNull Instant nextActionAt,
            @NotNull Long expectedVersion
    ) {}

    public record WithdrawRequest(
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record ConfirmRequest(@NotNull Long expectedVersion) {}

    public record ReturnRequest(
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record Response(
            UUID id,
            UUID customerId,
            String customerName,
            UUID businessCycleId,
            int businessCycleNumber,
            String businessCycleTitle,
            BusinessCycleStatus businessCycleStatus,
            boolean crossSale,
            UUID ownerMembershipId,
            String ownerDisplayName,
            UUID teamId,
            String teamName,
            UUID submittedByMembershipId,
            String submittedByDisplayName,
            List<DealForecastEvidenceType> evidenceTypes,
            String otherEvidenceDescription,
            List<UUID> evidenceInteractionIds,
            int evidenceCount,
            String factSummary,
            LocalDate expectedCloseDate,
            boolean overdue,
            String currentObstacle,
            String nextAction,
            Instant nextActionAt,
            DealForecastStatus status,
            String reviewNote,
            UUID reviewedByMembershipId,
            String reviewedByDisplayName,
            Instant reviewedAt,
            DealForecastOutcome outcome,
            Instant closedAt,
            Instant createdAt,
            Instant updatedAt,
            long version
    ) {}

    public record Summary(long pendingReview, long confirmedActive, long dueWithinSevenDays, long overdue) {}
}
