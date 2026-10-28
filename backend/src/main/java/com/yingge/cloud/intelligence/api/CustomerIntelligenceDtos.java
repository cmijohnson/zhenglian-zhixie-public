package com.yingge.cloud.intelligence.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CustomerIntelligenceDtos {
    private CustomerIntelligenceDtos() {}

    public record Evidence(
            String sourceType,
            String sourceKey,
            Instant sourceAt,
            String excerpt
    ) {}

    public record Version(
            UUID id,
            int versionNumber,
            String triggerType,
            String status,
            JsonNode result,
            String narrative,
            List<Evidence> evidence,
            String provider,
            String model,
            Instant sourceWatermark,
            Instant createdAt
    ) {}

    public record Overview(
            UUID customerId,
            boolean enabled,
            boolean processing,
            String processingStatus,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            Version latest
    ) {}

    public record Settings(
            boolean enabled,
            boolean autoProfileAnalysis,
            boolean autoTimelineAnalysis,
            String embeddingBaseUrl,
            String embeddingModel,
            int recentInteractionLimit,
            int semanticChunkLimit,
            boolean backfillEnabled,
            boolean backfillPaused,
            int nightlyRatePerMinute,
            long pendingJobs,
            long completedCustomers,
            long totalCustomers,
            Instant updatedAt,
            long version
    ) {}

    public record RuntimeStatus(
            Instant sampledAt,
            String pressureLevel,
            String pressureLabel,
            String pressureMessage,
            int availableProcessors,
            Double systemCpuPercent,
            Double processCpuPercent,
            Long systemMemoryUsedBytes,
            Long systemMemoryMaxBytes,
            Double systemMemoryPercent,
            long jvmHeapUsedBytes,
            long jvmHeapMaxBytes,
            Double jvmHeapPercent,
            int liveThreads,
            long uptimeSeconds,
            Integer dbActiveConnections,
            Integer dbIdleConnections,
            Integer dbTotalConnections,
            Integer dbAwaitingConnections,
            Integer dbMaxConnections,
            Double dbPressurePercent,
            long pendingJobs,
            long runningJobs,
            long failedJobs,
            Long oldestPendingSeconds
    ) {}

    public record UpdateSettingsRequest(
            boolean enabled,
            boolean autoProfileAnalysis,
            boolean autoTimelineAnalysis,
            @NotBlank String embeddingBaseUrl,
            @NotBlank String embeddingModel,
            @Min(1) @Max(30) int recentInteractionLimit,
            @Min(1) @Max(30) int semanticChunkLimit,
            boolean backfillEnabled,
            boolean backfillPaused,
            @Min(1) @Max(10) int nightlyRatePerMinute,
            Long expectedVersion
    ) {}
}
