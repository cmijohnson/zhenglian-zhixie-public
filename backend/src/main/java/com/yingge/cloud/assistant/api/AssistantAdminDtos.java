package com.yingge.cloud.assistant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AssistantAdminDtos {
    private AssistantAdminDtos() {}

    public record SettingsResponse(
            boolean enabled,
            String baseUrl,
            String model,
            boolean apiKeyConfigured,
            String apiKeySource,
            Instant updatedAt,
            long version
    ) {}

    public record UpdateRequest(
            boolean enabled,
            @NotBlank @Size(max = 500) String baseUrl,
            @NotBlank @Size(max = 200) String model,
            @Size(max = 2000) String apiKey,
            boolean clearApiKey,
            Long expectedVersion
    ) {}
}

