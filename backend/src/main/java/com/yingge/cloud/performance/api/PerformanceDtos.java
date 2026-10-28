package com.yingge.cloud.performance.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PerformanceDtos {
    private PerformanceDtos() {}

    public record EventTier(
            @NotBlank @Size(max = 120) String label,
            @PositiveOrZero int minParticipants,
            @PositiveOrZero Integer maxParticipants,
            @NotNull @DecimalMin("0.0") BigDecimal points
    ) {}

    public record CreateRuleRequest(
            @NotNull LocalDate effectiveFrom,
            @NotNull @DecimalMin("0.0") BigDecimal dailyBaseline,
            @NotBlank String calendarMode,
            Set<Integer> weekdays,
            Set<LocalDate> includedDates,
            Set<LocalDate> excludedDates,
            @NotNull LocalTime submissionOpensAt,
            @NotNull LocalTime submissionClosesAt,
            @NotBlank String shortfallMode,
            @NotNull @DecimalMin("0.0") BigDecimal ordinaryPoints,
            @NotNull @DecimalMin("0.0") BigDecimal deepPoints,
            @PositiveOrZero Integer ordinaryDailyCap,
            @PositiveOrZero Integer deepDailyCap,
            List<@Valid EventTier> eventTiers
    ) {}

    public record RuleResponse(
            UUID id, int versionNo, LocalDate effectiveFrom, BigDecimal dailyBaseline,
            String calendarMode, Set<Integer> weekdays, Set<LocalDate> includedDates,
            Set<LocalDate> excludedDates, LocalTime submissionOpensAt, LocalTime submissionClosesAt,
            String shortfallMode, BigDecimal ordinaryPoints, BigDecimal deepPoints,
            Integer ordinaryDailyCap, Integer deepDailyCap, List<EventTier> eventTiers,
            Instant createdAt
    ) {}

    public record ParticipantRequest(
            @NotNull UUID membershipId,
            @NotNull LocalDate startsOn,
            LocalDate endsOn
    ) {}

    public record ParticipantUpdateRequest(LocalDate endsOn, @NotNull Long expectedVersion) {}

    public record ParticipantResponse(
            UUID id, UUID membershipId, String displayName, UUID teamId, String teamName,
            LocalDate startsOn, LocalDate endsOn, boolean activeToday, long version
    ) {}

    public record InteractionOption(
            UUID interactionId, UUID customerId, String customerName, Instant contactedAt,
            String channel, String summary
    ) {}

    public record CreateClaimRequest(@NotNull UUID interactionId, @NotBlank String requestedType) {}

    public record ReviewClaimRequest(
            @NotBlank String decision,
            String approvedType,
            @Size(max = 2000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record EvidenceResponse(
            UUID id, String ownerType, UUID ownerId, String fileName, String contentType,
            long fileSize, Instant createdAt
    ) {}

    public record ClaimResponse(
            UUID id, UUID membershipId, String memberDisplayName, UUID interactionId, UUID customerId,
            String customerName, LocalDate performanceDate, String requestedType, String approvedType,
            String status, String reviewReason, Instant submittedAt, Instant reviewedAt,
            List<EvidenceResponse> evidence, long version
    ) {}

    public record CreateActivityRequest(
            @NotNull LocalDate eventDate,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2000) String goal,
            @PositiveOrZero int expectedParticipants,
            @NotBlank @Size(max = 6000) String description
    ) {}

    public record ReviewActivityRequest(
            @NotBlank String decision,
            @Size(max = 2000) String reason,
            @NotNull Long expectedVersion
    ) {}

    public record CompleteActivityRequest(
            @PositiveOrZero int actualParticipants,
            @NotNull Long expectedVersion
    ) {}

    public record ActivityAllocation(
            @NotNull UUID membershipId,
            @NotNull @DecimalMin("0.0") BigDecimal points
    ) {}

    public record AllocateActivityRequest(
            @PositiveOrZero int verifiedParticipants,
            @NotEmpty List<@Valid ActivityAllocation> allocations,
            @NotNull Long expectedVersion
    ) {}

    public record ActivityResponse(
            UUID id, UUID organizerMembershipId, String organizerDisplayName, LocalDate eventDate,
            String title, String goal, int expectedParticipants, String description,
            Integer actualParticipants, Integer verifiedParticipants, String tierLabel,
            BigDecimal totalPoints, String status, String reviewReason,
            List<ActivityAllocation> allocations, List<EvidenceResponse> evidence,
            Instant createdAt, long version
    ) {}

    public record ManualEntryRequest(
            @NotNull UUID membershipId,
            @NotNull LocalDate performanceDate,
            @NotBlank String entryType,
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 2000) String reason,
            @NotNull BigDecimal points,
            UUID relatedLedgerId
    ) {}

    public record LedgerEntry(
            UUID id, UUID membershipId, String memberDisplayName, LocalDate performanceDate,
            String category, UUID sourceId, BigDecimal points, String description, Instant createdAt
    ) {}

    public record ScoreBreakdown(
            BigDecimal businessPoints, BigDecimal logisticsPoints, BigDecimal correctionPoints,
            BigDecimal earnedPoints, BigDecimal baseline, BigDecimal shortfall,
            BigDecimal deduction, BigDecimal finalPoints
    ) {}

    public record TodayResponse(
            LocalDate date, boolean participant, String dayStatus, boolean submissionOpen,
            LocalTime opensAt, LocalTime closesAt, RuleResponse rule, ScoreBreakdown score,
            long pendingClaims, long pendingActivities, int approvedDeepCount, int pendingDeepCount,
            List<InteractionOption> availableInteractions, List<ClaimResponse> claims,
            List<ActivityResponse> activities, List<LedgerEntry> ledger
    ) {}

    public record LeaderboardRow(
            int rank, UUID membershipId, String displayName, UUID teamId, String teamName,
            BigDecimal earnedPoints, BigDecimal finalPoints, int scoredDays, int achievedDays
    ) {}

    public record HistoryPoint(
            LocalDate date, BigDecimal earnedPoints, BigDecimal finalPoints,
            BigDecimal baseline, boolean settled
    ) {}

    public record DayMember(
            UUID membershipId, String displayName, UUID teamId, String teamName,
            ScoreBreakdown score, boolean settled
    ) {}

    public record DayResponse(
            LocalDate date, String status, RuleResponse rule, long pendingClaims,
            long pendingActivities, List<DayMember> members, long version
    ) {}

    public record ReopenDayRequest(@NotBlank @Size(max = 2000) String reason, @NotNull Long expectedVersion) {}
}
