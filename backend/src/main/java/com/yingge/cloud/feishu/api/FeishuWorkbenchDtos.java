package com.yingge.cloud.feishu.api;

import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.domain.CustomerVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class FeishuWorkbenchDtos {
    private FeishuWorkbenchDtos() {}

    public record BootstrapResponse(boolean bound, String feishuDisplayName, String memberDisplayName,
                                    boolean writable, boolean viewer, String customerTableUrl,
                                    Set<String> capabilities) {}

    public record CustomerDraftRequest(
            @NotBlank @Size(max = 160) String fullName,
            @Size(max = 160) String nickname,
            @Size(max = 80) String phone,
            @Size(max = 120) String wechatId,
            @Size(max = 120) String qqId,
            @Size(max = 200) String school,
            @Size(max = 200) String highSchool,
            @Size(max = 160) String major,
            @Size(max = 80) String grade,
            @Size(max = 120) String city,
            @Size(max = 200) String sourceLocation,
            @Size(max = 4000) String demandSummary,
            Set<@Size(max = 80) String> focusDirections,
            @Size(max = 160) String sourceChannel,
            Instant nextFollowUpAt
    ) {}

    public record DraftResponse(UUID id, String status, CustomerDtos.DuplicateResponse duplicate,
                                long version, Instant expiresAt) {}

    public record ConfirmDraftRequest(UUID ownerMembershipId, @NotNull CustomerVisibility visibility,
                                      UUID teamId, @Size(max = 500) String duplicateOverrideReason,
                                      @NotNull Long expectedVersion) {}

    public record TaskReviewRequest(@NotNull UUID assignmentId, @NotBlank String status,
                                    @Size(max = 2000) String feedback) {}

    public record ControlledCustomerUpdateRequest(
            @NotBlank @Size(max = 80) String currentStatus,
            @NotBlank @Size(max = 80) String intentionLevel,
            @NotBlank @Size(max = 80) String urgencyLevel,
            Instant nextFollowUpAt,
            @NotNull Long expectedVersion
    ) {}

    public record DealMemberResponse(UUID membershipId, String displayName, String teamNames) {}

    public record DealDraftCreateRequest(@NotNull UUID customerId) {}

    public record DealAllocationRequest(@NotNull UUID membershipId, @NotNull @Min(0) Long bonusCents) {}

    public record DealDraftUpdateRequest(
            @NotNull UUID primaryMembershipId,
            @NotNull @Size(max = 2) List<UUID> assistantMembershipIds,
            @NotNull @Min(0) Long orderAmountCents,
            @NotNull @Min(0) Long totalBonusCents,
            @NotNull List<@NotNull DealAllocationRequest> allocations,
            @NotNull Long expectedVersion
    ) {}

    public record DealDraftResponse(
            UUID id,
            UUID customerId,
            String customerName,
            UUID ownerMembershipId,
            String ownerDisplayName,
            UUID primaryMembershipId,
            List<UUID> assistantMembershipIds,
            long orderAmountCents,
            long totalBonusCents,
            List<DealAllocationRequest> allocations,
            String status,
            UUID confirmedRequestId,
            long version,
            Instant expiresAt
    ) {}

    public record DealDraftConfirmRequest(@NotNull Long expectedVersion) {}

    public record DealConfirmationResult(
            FeishuDealBotDtos.DealSummaryResponse confirmation,
            String broadcastStatus
    ) {}
}
