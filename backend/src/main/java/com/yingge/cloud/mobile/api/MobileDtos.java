package com.yingge.cloud.mobile.api;

import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class MobileDtos {
    private MobileDtos() {}

    public enum Platform { ANDROID, IOS }

    public record LoginRequest(
            @NotBlank @Size(max = 160) String username,
            @NotBlank @Size(max = 200) String password,
            @NotBlank @Size(max = 160) String deviceName
    ) {}

    public record RefreshRequest(@NotBlank @Size(max = 512) String refreshToken) {}

    public record RegistrationOptionsRequest(@NotBlank @Size(max = 100) String registrationCode) {}

    public record RegisterRequest(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 120) String realName,
            @NotBlank @Size(max = 200) String password,
            @NotBlank @Size(max = 100) String registrationCode,
            @NotNull UUID teamId
    ) {}

    public record Actor(
            UUID userId,
            UUID membershipId,
            UUID organizationId,
            String username,
            String displayName,
            Set<String> roles,
            boolean passwordChangeRequired
    ) {
        public static Actor from(CloudPrincipal principal) {
            var actor = principal.actor();
            return new Actor(actor.userId(), actor.membershipId(), actor.organizationId(), actor.username(),
                    actor.displayName(), actor.roles().stream().map(Enum::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()), principal.passwordChangeRequired());
        }
    }

    public record TokenResponse(
            String accessToken,
            Instant accessExpiresAt,
            String refreshToken,
            Instant refreshExpiresAt,
            Actor actor
    ) {}

    public record Device(
            UUID id,
            Platform platform,
            String deviceName,
            String appVersion,
            boolean current,
            Instant createdAt,
            Instant lastUsedAt,
            Instant revokedAt
    ) {}

    public record Bootstrap(
            Actor actor,
            Instant serverTime,
            String organizationTimeZone,
            Set<String> availableModes,
            Set<String> capabilities,
            Unread unread
    ) {}

    public record AdminOverview(
            long pendingDealRequests,
            long pendingClassificationReviews,
            long dueOwnershipDeadlines,
            long openPoolEntries,
            long pendingSecondLineRecruitments,
            long pendingPerformanceClaims,
            long pendingPerformanceActivities,
            long overdueConsultationFeedback,
            long failedIntelligenceJobs,
            Instant generatedAt
    ) {}

    public record Unread(long urgentReminders, long notifications) {}

    public record CustomerInsight(
            String content,
            Instant generatedAt,
            Instant timelineUpdatedAt,
            String traceId
    ) {}

    public record DashboardGreeting(
            String salutation,
            String subtitle,
            boolean generatedByAi,
            Instant validUntil,
            String traceId
    ) {}

    public record PushRegistrationRequest(
            @NotBlank String provider,
            @NotBlank @Size(min = 16, max = 2048) String registrationId,
            @Size(max = 80) String vendor
    ) {}

    public record NotificationPreferences(
            @NotNull Boolean urgentReminders,
            @NotNull Boolean tasks,
            @NotNull Boolean consultations,
            @NotNull Boolean announcements,
            @NotNull Boolean releases
    ) {}

    public record WebSessionTicketRequest(@NotBlank String target) {}

    public record WebSessionTicketResponse(String ticket, String exchangeUrl, Instant expiresAt) {}

    public record AppRelease(
            UUID id,
            Platform platform,
            long versionCode,
            String versionName,
            long minimumSupportedVersionCode,
            boolean mandatory,
            String notes,
            Long apkSize,
            String apkSha256,
            String manifestSignature
    ) {}
}
