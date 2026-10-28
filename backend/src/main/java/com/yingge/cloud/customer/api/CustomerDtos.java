package com.yingge.cloud.customer.api;

import com.yingge.cloud.customer.domain.CustomerVisibility;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.customer.domain.LeadReviewStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class CustomerDtos {
    private CustomerDtos() {
    }

    public record UpsertRequest(
            @NotBlank @Size(max = 160) String fullName,
            @Size(max = 160) String nickname,
            @Size(max = 80) String phone,
            @Size(max = 120) String wechatId,
            @Size(max = 120) String qqId,
            @Size(max = 200) String school,
            @Size(max = 160) String major,
            @Size(max = 80) String grade,
            @Size(max = 120) String city,
            @Size(max = 200) String highSchool,
            @Size(max = 200) String sourceLocation,
            String identityType,
            Set<@Size(max = 80) String> focusDirections,
            @Size(max = 4000) String demandSummary,
            String intentionLevel,
            String urgencyLevel,
            String verificationStatus,
            UUID sourceDictionaryId,
            @Size(max = 160) String sourceChannel,
            String currentStatus,
            @Size(max = 10000) String ownerNotes,
            Instant nextFollowUpAt,
            Instant friendAddedAt,
            Instant friendTrustedAt,
            Instant friendQualifiedAt,
            UUID ownerMembershipId,
            UUID teamId,
            CustomerVisibility visibility,
            Set<UUID> tagIds,
            @Size(max = 500) String duplicateOverrideReason,
            Long expectedVersion
    ) {}

    public record Response(
            UUID id, UUID ownerMembershipId, String ownerDisplayName,
            UUID createdByMembershipId, String creatorDisplayName, UUID teamId, CustomerVisibility visibility,
            String fullName, String nickname, String phone, String wechatId, String qqId,
            String school, String major, String grade, String city, String highSchool, String sourceLocation,
            String identityType,
            Set<String> focusDirections, String demandSummary, String intentionLevel, String urgencyLevel,
            String verificationStatus, UUID sourceDictionaryId, String sourceChannel, String currentStatus,
            String ownerNotes, Instant lastContactAt, Instant nextFollowUpAt,
            Instant friendAddedAt, Instant friendTrustedAt, Instant friendQualifiedAt, Instant archivedAt,
            LeadReviewStatus leadReviewStatus, String leadReviewReason,
            UUID leadReviewedByMembershipId, String leadReviewerDisplayName, Instant leadReviewedAt,
            Instant createdAt, Instant updatedAt, long version, List<TagSummary> tags,
            String dealState, boolean canRequestDeal, Instant dealRequestedAt
    ) {
        public static Response from(CustomerEntity customer) {
            return new Response(
                    customer.getId(), customer.getOwnerMembershipId(), customer.getOwnerDisplayName(),
                    customer.getCreatedByMembershipId(), customer.getCreatorDisplayName(),
                    customer.getTeamId(), customer.getVisibility(),
                    customer.getFullName(), customer.getNickname(), customer.getPhone(), customer.getWechatId(), customer.getQqId(),
                    customer.getSchool(), customer.getMajor(), customer.getGrade(), customer.getCity(),
                    customer.getHighSchool(), customer.getSourceLocation(), customer.getIdentityType(),
                    customer.getFocusDirections(), customer.getDemandSummary(), customer.getIntentionLevel(), customer.getUrgencyLevel(),
                    customer.getVerificationStatus(), customer.getSourceDictionaryId(), customer.getSourceChannelLegacy(),
                    customer.getCurrentStatus(), customer.getOwnerNotes(), customer.getLastContactAt(), customer.getNextFollowUpAt(),
                    customer.getFriendAddedAt(), customer.getFriendTrustedAt(), customer.getFriendQualifiedAt(),
                    customer.getArchivedAt(), customer.getLeadReviewStatus(), customer.getLeadReviewReason(),
                    customer.getLeadReviewedByMembershipId(), customer.getLeadReviewerDisplayName(), customer.getLeadReviewedAt(),
                    customer.getCreatedAt(), customer.getUpdatedAt(), customer.getVersion(),
                    customer.getTags().stream().filter(com.yingge.cloud.tag.infrastructure.TagEntity::isActive)
                            .map(tag -> new TagSummary(tag.getId(), tag.getName(), tag.getCategory(), tag.getColor())).toList(),
                    "NONE", false, null
            );
        }

        public Response withDealState(String state, boolean requestable, Instant requestedAt) {
            return new Response(id, ownerMembershipId, ownerDisplayName, createdByMembershipId, creatorDisplayName,
                    teamId, visibility, fullName, nickname, phone, wechatId, qqId, school, major, grade, city,
                    highSchool, sourceLocation, identityType, focusDirections, demandSummary, intentionLevel,
                    urgencyLevel, verificationStatus, sourceDictionaryId, sourceChannel, currentStatus, ownerNotes,
                    lastContactAt, nextFollowUpAt, friendAddedAt, friendTrustedAt, friendQualifiedAt, archivedAt,
                    leadReviewStatus, leadReviewReason, leadReviewedByMembershipId, leadReviewerDisplayName,
                    leadReviewedAt, createdAt, updatedAt, version, tags, state, requestable, requestedAt);
        }
    }

    public record TagSummary(UUID id, String name, String category, String color) {}
    public record DuplicateRequest(
            @NotBlank String fullName,
            String major,
            String phone,
            String wechatId,
            String qqId,
            UUID excludeCustomerId
    ) {}
    public record DuplicateMatch(UUID customerId, String fullName, List<String> reasons) {}
    public record DuplicateResponse(
            boolean blocking,
            boolean requiresConfirmation,
            int hiddenExactMatches,
            List<DuplicateMatch> matches
    ) {}
    public record LeadReviewRequest(@NotNull LeadReviewStatus status, @Size(max = 1000) String reason) {}
    public record CollaborationMember(UUID membershipId, String displayName, String teamNames) {}
    public record CollaborationOverview(
            UUID ownerMembershipId,
            boolean canManage,
            boolean canTransferOwnership,
            boolean canEditProfile,
            boolean canArchive,
            boolean canEditTimeline,
            List<CollaborationMember> collaborators,
            List<CollaborationMember> candidates,
            List<CollaborationMember> transferCandidates
    ) {}
    public record UpdateCollaboratorsRequest(Set<UUID> membershipIds) {}
    public record TransferOwnershipRequest(
            @NotNull UUID targetMembershipId,
            @NotNull Long expectedVersion,
            @Size(max = 500) String reason
    ) {}
}
