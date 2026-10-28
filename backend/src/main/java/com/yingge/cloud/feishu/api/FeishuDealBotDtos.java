package com.yingge.cloud.feishu.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class FeishuDealBotDtos {
    private FeishuDealBotDtos() {}

    public record SettingsRequest(
            Boolean enabled,
            String submissionChatId,
            String broadcastChatId,
            @Min(0) Long defaultBonusCents,
            @Size(max = 300) String broadcastTitle,
            @Size(max = 300) String leaderboardTitle,
            @Size(max = 500) String leaderboardFooter,
            Boolean privacyAcknowledged,
            Boolean quickCustomerCreationEnabled,
            Boolean financialVisibilityAcknowledged,
            @NotNull Long expectedVersion
    ) {}

    public record SettingsResponse(
            UUID id,
            boolean enabled,
            String submissionChatId,
            String submissionChatName,
            String broadcastChatId,
            String broadcastChatName,
            String botName,
            long defaultBonusCents,
            String broadcastTitle,
            String leaderboardTitle,
            String leaderboardFooter,
            Instant privacyAcknowledgedAt,
            boolean quickCustomerCreationEnabled,
            Instant financialVisibilityAcknowledgedAt,
            long version,
            Instant updatedAt
    ) {}

    public record ChatResponse(String chatId, String name, String description) {}
    public record TestResponse(boolean ok, String botName, String submissionChatName,
                               String broadcastChatName, Instant testedAt) {}

    public record ParticipantResponse(UUID membershipId, String displayName, String role, long bonusCents) {}
    public record ConfirmationResponse(UUID id, UUID customerId, String customerName, String status,
                                       String source, String confirmationChannel, Instant confirmedAt,
                                       Long orderAmountCents, long totalBonusCents, boolean needsAttribution,
                                       List<ParticipantResponse> participants, long version) {}
    public record ConfirmationPage(List<ConfirmationResponse> content, int page, int size,
                                   long totalElements, int totalPages) {}

    public record ReversalRequest(@NotBlank @Size(max = 500) String reason, @NotNull Long expectedVersion) {}
    public record AllocationRequest(@NotNull UUID membershipId, @Min(0) Long bonusCents) {}
    public record CompleteAttributionRequest(@NotNull UUID primaryMembershipId,
                                             @Size(max = 2) List<UUID> assistantMembershipIds,
                                             @Min(1) Long orderAmountCents,
                                             @Min(0) Long totalBonusCents,
                                             @NotNull List<AllocationRequest> allocations,
                                             @NotNull Long expectedVersion) {}

    public record LeaderboardEntry(int rank, UUID membershipId, String displayName,
                                   long dealCount, long primaryOrderAmountCents,
                                   long primaryBonusCents, Instant reachedAt) {}
    public record LeaderboardResponse(LocalDate from, LocalDate to, long historicalDealCount,
                                      long historicalOrderAmountCents, long historicalBonusCents,
                                      List<LeaderboardEntry> entries,
                                      String previewText) {}

    public record DealSummaryResponse(UUID requestId, UUID customerId, String status,
                                      String confirmationChannel, Instant confirmedAt,
                                      Long orderAmountCents, long totalBonusCents,
                                      List<ParticipantResponse> participants) {}

    public record MessageFailureResponse(UUID id, String purpose, String chatId, int attemptCount,
                                         String errorCode, Instant createdAt, Instant updatedAt) {}
}
