package com.yingge.cloud.feishu.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class FeishuIntegrationDtos {
    private FeishuIntegrationDtos() {}

    public record ConfigurationRequest(
            @NotBlank @Size(max = 128) String appId,
            @Size(max = 512) String appSecret,
            @NotBlank @Size(max = 256) String baseAppToken,
            Boolean active,
            Boolean oauthEnabled,
            Boolean inboundSyncEnabled,
            Boolean riskAcknowledged,
            Long expectedVersion
    ) {
        public ConfigurationRequest(String appId, String appSecret, String baseAppToken, Boolean active,
                                    Boolean riskAcknowledged, Long expectedVersion) {
            this(appId, appSecret, baseAppToken, active, null, null, riskAcknowledged, expectedVersion);
        }
    }

    public record ConfigurationResponse(
            UUID id,
            String appId,
            boolean secretConfigured,
            String baseAppToken,
            String tableId,
            String tableName,
            boolean active,
            boolean oauthEnabled,
            boolean inboundSyncEnabled,
            String schemaState,
            String eventState,
            Instant lastEventAt,
            String lastEventErrorCode,
            Instant riskAcknowledgedAt,
            Instant lastTestedAt,
            Instant lastSuccessAt,
            String lastErrorCode,
            long pendingCount,
            long permanentFailureCount,
            long version,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record ConnectionTestResponse(boolean ok, Instant testedAt) {}

    public record ProvisionResponse(String tableId, String tableName, int fieldCount, String schemaState) {}

    public record SchemaResponse(boolean provisioned, String state, List<FieldStatus> fields, Instant checkedAt) {}

    public record FieldStatus(String key, String expectedName, String remoteName, int expectedType,
                              Integer remoteType, boolean present, boolean compatible) {}

    public record InitialSyncPreview(long totalCustomers, long privateCustomers, long archivedCustomers,
                                     List<String> sensitiveFields, String warning) {}

    public record RunResponse(UUID id, String type, String status, int totalCount, int successCount,
                              int failedCount, int skippedCount, Instant startedAt, Instant completedAt,
                              String lastErrorCode, Instant createdAt) {}

    public record FailureResponse(UUID queueId, UUID customerId, int attemptCount, String errorCode,
                                  Instant nextAttemptAt, Instant updatedAt) {}

    public record InboundStatusResponse(String connectionState, Instant lastEventAt, String lastEventErrorCode,
                                        long pendingCount, long appliedCount, long conflictCount,
                                        long rejectedCount, long failedCount) {}

    public record InboundEventResponse(UUID inboxId, String eventType, String actionKind, String recordId,
                                       String status, int attemptCount, String reasonCode,
                                       UUID customerId, UUID actorMembershipId, List<String> changedFields,
                                       Instant receivedAt, Instant processedAt) {}
}
