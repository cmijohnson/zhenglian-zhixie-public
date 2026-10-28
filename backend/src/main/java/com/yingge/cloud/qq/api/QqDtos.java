package com.yingge.cloud.qq.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.yingge.cloud.assistant.api.AssistantDtos;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class QqDtos {
    private QqDtos() {}

    public record Connection(UUID id, String status, String maskedQq, String qrCodeDataUrl,
                             Instant lastHeartbeatAt, Instant createdAt) {}
    public record Friend(String uid, String uin, String nick, String remark) {}
    public record FriendPage(List<Friend> items, int page, int totalPages, long totalElements) {}
    public record WorkbenchBootstrap(String status, String maskedQq, String workbenchUrl,
                                     String websocketUrl, int rawRetentionDays, long exportQuotaBytes) {}

    public record PreviewRequest(
            @NotNull UUID customerId,
            @Min(1) @Max(999) int chatType,
            @NotBlank @Size(max = 256) String peerUid,
            @NotBlank @Size(max = 120) String peerQq,
            @Size(max = 240) String sessionName,
            @Size(max = 256) String targetSenderUid,
            @Size(max = 120) String targetSenderQq,
            boolean groupContextConfirmed,
            @NotNull Instant rangeStart,
            @NotNull Instant rangeEnd,
            boolean bindMissingQq
    ) {}

    public record Preview(
            UUID importId,
            UUID customerId,
            int chatType,
            String peerUid,
            String peerQq,
            String sessionName,
            String targetSenderUid,
            String targetSenderQq,
            boolean groupContextConfirmed,
            int messageCount,
            Instant rangeStart,
            Instant rangeEnd,
            Instant expiresAt,
            AssistantDtos.QqConversationDraft draft,
            JsonNode messages
    ) {}

    public record ConfirmRequest(
            @NotBlank @Size(max = 120) String idempotencyKey,
            @NotNull @Valid AssistantDtos.QqConversationDraft draft
    ) {}

    public record ConfirmResult(UUID importId, UUID interactionId, String status) {}
    public record AdminConnection(UUID id, UUID membershipId, String memberName, String status,
                                  String maskedQq, Instant lastHeartbeatAt, Instant createdAt) {}
}
