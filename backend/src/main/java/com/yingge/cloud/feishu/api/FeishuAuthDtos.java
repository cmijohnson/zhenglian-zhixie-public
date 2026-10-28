package com.yingge.cloud.feishu.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FeishuAuthDtos {
    private FeishuAuthDtos() {}

    public record BindingResponse(boolean bound, boolean oauthAvailable, boolean httpsRequired,
                                  UUID bindingId, String tenantKey, String feishuUserId,
                                  String displayName, Instant boundAt, Instant lastLoginAt) {}

    public record ConfirmRequest(@NotNull UUID intentId, @NotBlank String username, @NotBlank String password) {}

    public record UnbindRequest(@NotBlank String currentPassword) {}

    public record AdminBindingResponse(UUID id, UUID membershipId, UUID userId, String memberDisplayName,
                                       String tenantKey, String feishuUserId, String feishuDisplayName,
                                       boolean active, Instant boundAt, Instant lastLoginAt, Instant revokedAt) {}

    public record LoginOption(UUID integrationId, String organizationName) {}

    public record LoginOptionsResponse(boolean available, List<LoginOption> organizations, boolean httpsRequired) {}
}
