package com.yingge.cloud.market.api;

import com.yingge.cloud.market.domain.CustomerClassLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MarketDtos {
    private MarketDtos() {}

    public record ClassificationRequest(
            @NotNull CustomerClassLevel level,
            @NotBlank @Size(max = 4000) String recognitionReason,
            @NotBlank @Size(max = 4000) String potentialDemand,
            @Size(max = 20) Set<@Size(max = 500) String> aSignals,
            @NotEmpty @Size(max = 50) Set<UUID> evidenceInteractionIds,
            @Size(max = 1000) String correctionReason
    ) {}

    public record ClassificationResponse(
            UUID id,
            UUID customerId,
            int revision,
            CustomerClassLevel level,
            String recognitionReason,
            String potentialDemand,
            List<String> aSignals,
            List<UUID> evidenceInteractionIds,
            UUID submittedByMembershipId,
            UUID teamIdSnapshot,
            String source,
            String correctionReason,
            UUID previousClassificationId,
            Instant createdAt
    ) {}

    public record ReviewQueueItem(
            UUID classificationId,
            UUID customerId,
            String customerName,
            CustomerClassLevel level,
            UUID submittedByMembershipId,
            String submittedByDisplayName,
            UUID teamId,
            LocalDate recommendationMonth,
            boolean recommended,
            boolean selected,
            String reviewStatus,
            Instant createdAt
    ) {}

    public record ReviewRequest(
            @NotNull ReviewDecision decision,
            @Size(max = 2000) String note,
            @Valid ClassificationRequest correction
    ) {}

    public enum ReviewDecision { CONFIRMED, CORRECTED }

    public record MemberAnomaly(
            UUID membershipId,
            String displayName,
            UUID teamId,
            long totalDecisions,
            long aDecisions,
            double aRate,
            double teamMedianRate,
            boolean abnormal
    ) {}

    public record ClassificationDataIssue(
            UUID customerId,
            String customerName,
            String issueType,
            String qualityTags,
            String structuredLevel
    ) {}

    public record DateRange(@NotNull LocalDate start, @NotNull LocalDate end) {}

    public record DeadlineRuleRequest(
            @NotNull @Future Instant effectiveFrom,
            String mode,
            @Size(max = 50) List<@Valid DateRange> peakPeriods,
            @Size(max = 100) List<@Valid DateRange> pausePeriods,
            Integer aPeakHours,
            Integer bPeakHours,
            Integer aOffPeakHours,
            Integer bOffPeakHours,
            Boolean weekendsCount
    ) {}

    public record DeadlineRuleResponse(
            UUID id,
            int version,
            Instant effectiveFrom,
            String mode,
            List<DateRange> peakPeriods,
            List<DateRange> pausePeriods,
            int aPeakHours,
            int bPeakHours,
            int aOffPeakHours,
            int bOffPeakHours,
            boolean weekendsCount,
            UUID createdByMembershipId,
            Instant createdAt
    ) {}

    public record DeadlineSnapshot(
            UUID customerId,
            UUID cycleId,
            CustomerClassLevel classificationLevel,
            UUID ruleVersionId,
            UUID baseInteractionId,
            Instant computedDeadline,
            boolean noReplyExtensionUsed,
            String mode,
            Instant calculatedAt
    ) {}
}
