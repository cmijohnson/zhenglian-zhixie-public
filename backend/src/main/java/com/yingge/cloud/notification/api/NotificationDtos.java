package com.yingge.cloud.notification.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class NotificationDtos {
    private NotificationDtos() {}

    public record NotificationResponse(UUID id, String eventType, String title, String summary,
                                       String targetPath, boolean read, Instant createdAt, Instant expiresAt) {}

    public record InboxResponse(long unreadCount, java.util.List<NotificationResponse> items) {}

    public record WebhookRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 1000) String webhookUrl,
            @NotEmpty Set<String> eventTypes,
            Boolean active,
            Long expectedVersion
    ) {}

    public record WebhookResponse(UUID id, String name, Set<String> eventTypes, boolean active,
                                  boolean secretConfigured, long version, Instant createdAt, Instant updatedAt) {}

    public record DeliveryResponse(UUID id, UUID channelId, String channelName, String eventType,
                                   String title, String status, int attemptCount, String lastErrorCode,
                                   Instant nextAttemptAt, Instant deliveredAt, Instant createdAt) {}
}
