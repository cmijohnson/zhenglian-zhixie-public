package com.yingge.cloud.followup.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class FollowUpDtos {
    private FollowUpDtos() {}

    public record Policy(
            boolean aClassStaleEnabled,
            int staleAfterHours,
            LocalTime notifyAt,
            boolean qqEnabled,
            boolean abAutoReleaseEnabled,
            Instant abAutoReleaseEffectiveFrom,
            long version
    ) {}

    public record UpdatePolicy(
            boolean aClassStaleEnabled,
            @Min(24) @Max(720) int staleAfterHours,
            @NotNull LocalTime notifyAt,
            boolean qqEnabled,
            boolean abAutoReleaseEnabled,
            @NotNull Long expectedVersion
    ) {}

    public record TimelineItem(Instant contactedAt, String channel, String summary, String details, String outcome) {}

    public record Recruitment(
            UUID id,
            UUID customerId,
            String fullName,
            String phone,
            String wechatId,
            String qqId,
            String school,
            String major,
            String grade,
            String highSchool,
            String sourceLocation,
            String demandSummary,
            String ownerDisplayName,
            int requiredCount,
            int claimedCount,
            Instant openedAt,
            List<TimelineItem> recentTimeline
    ) {}

    public record ClaimResult(UUID recruitmentId, UUID customerId, int remainingSlots, boolean closed) {}
}
