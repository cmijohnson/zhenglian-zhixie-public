package com.yingge.cloud.customer.infrastructure;

import com.yingge.cloud.customer.domain.CustomerVisibility;
import com.yingge.cloud.customer.domain.LeadReviewStatus;
import com.yingge.cloud.tag.infrastructure.TagEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "customers")
public class CustomerEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "owner_membership_id", nullable = false)
    private UUID ownerMembershipId;
    @Formula("(select account.display_name from memberships member join user_accounts account on account.id=member.user_id where member.id=owner_membership_id)")
    private String ownerDisplayName;
    @Column(name = "team_id")
    private UUID teamId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CustomerVisibility visibility;
    @Column(name = "full_name", nullable = false)
    private String fullName;
    private String nickname;
    private String phone;
    @Column(name = "normalized_phone")
    private String normalizedPhone;
    @Column(name = "wechat_id")
    private String wechatId;
    @Column(name = "normalized_wechat_id")
    private String normalizedWechatId;
    @Column(name = "qq_id")
    private String qqId;
    @Column(name = "normalized_qq_id")
    private String normalizedQqId;
    private String school;
    private String major;
    private String grade;
    private String city;
    @Column(name = "high_school")
    private String highSchool;
    @Column(name = "source_location")
    private String sourceLocation;
    @Column(name = "identity_type", nullable = false)
    private String identityType;
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "customer_focus_directions", joinColumns = @JoinColumn(name = "customer_id"))
    @Column(name = "direction")
    private Set<String> focusDirections = new LinkedHashSet<>();
    @Column(name = "demand_summary")
    private String demandSummary;
    @Column(name = "intention_level", nullable = false)
    private String intentionLevel;
    @Column(name = "urgency_level", nullable = false)
    private String urgencyLevel;
    @Column(name = "verification_status", nullable = false)
    private String verificationStatus;
    @Column(name = "source_dictionary_id")
    private UUID sourceDictionaryId;
    @Column(name = "source_channel_legacy")
    private String sourceChannelLegacy;
    @Column(name = "contact_completion_status", nullable = false)
    private String contactCompletionStatus;
    @Column(name = "current_status", nullable = false)
    private String currentStatus;
    @Column(name = "owner_notes")
    private String ownerNotes;
    @Column(name = "last_contact_at")
    private Instant lastContactAt;
    @Column(name = "next_follow_up_at")
    private Instant nextFollowUpAt;
    @Column(name = "friend_added_at")
    private Instant friendAddedAt;
    @Column(name = "friend_trusted_at")
    private Instant friendTrustedAt;
    @Column(name = "friend_qualified_at")
    private Instant friendQualifiedAt;
    @Column(name = "archived_at")
    private Instant archivedAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "lead_review_status", nullable = false)
    private LeadReviewStatus leadReviewStatus;
    @Column(name = "lead_review_reason")
    private String leadReviewReason;
    @Column(name = "lead_reviewed_by_membership_id")
    private UUID leadReviewedByMembershipId;
    @Formula("(select account.display_name from memberships member join user_accounts account on account.id=member.user_id where member.id=lead_reviewed_by_membership_id)")
    private String leadReviewerDisplayName;
    @Column(name = "lead_reviewed_at")
    private Instant leadReviewedAt;
    @Column(name = "created_by_membership_id", nullable = false)
    private UUID createdByMembershipId;
    @Formula("(select account.display_name from memberships member join user_accounts account on account.id=member.user_id where member.id=created_by_membership_id)")
    private String creatorDisplayName;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "customer_tags",
            joinColumns = @JoinColumn(name = "customer_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<TagEntity> tags = new LinkedHashSet<>();

    protected CustomerEntity() {
    }

    public CustomerEntity(UUID organizationId, UUID ownerMembershipId, UUID createdByMembershipId) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.ownerMembershipId = ownerMembershipId;
        this.createdByMembershipId = createdByMembershipId;
        this.visibility = CustomerVisibility.PRIVATE;
        this.leadReviewStatus = LeadReviewStatus.PENDING;
        this.contactCompletionStatus = "COMPLETE";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void apply(CustomerValues values) {
        this.ownerMembershipId = values.ownerMembershipId();
        this.teamId = values.teamId();
        this.visibility = values.visibility();
        this.fullName = values.fullName();
        this.nickname = values.nickname();
        this.phone = values.phone();
        this.normalizedPhone = values.normalizedPhone();
        this.wechatId = values.wechatId();
        this.normalizedWechatId = values.normalizedWechatId();
        this.qqId = values.qqId();
        this.normalizedQqId = values.normalizedQqId();
        this.school = values.school();
        this.major = values.major();
        this.grade = values.grade();
        this.city = values.city();
        this.highSchool = values.highSchool();
        this.sourceLocation = values.sourceLocation();
        this.identityType = values.identityType();
        this.focusDirections.clear();
        this.focusDirections.addAll(values.focusDirections());
        this.demandSummary = values.demandSummary();
        this.intentionLevel = values.intentionLevel();
        this.urgencyLevel = values.urgencyLevel();
        this.verificationStatus = values.verificationStatus();
        this.sourceDictionaryId = values.sourceDictionaryId();
        this.sourceChannelLegacy = values.sourceChannelLegacy();
        if (hasContact(values.phone(), values.wechatId(), values.qqId())) {
            this.contactCompletionStatus = "COMPLETE";
        }
        this.currentStatus = values.currentStatus();
        this.ownerNotes = values.ownerNotes();
        this.nextFollowUpAt = values.nextFollowUpAt();
        this.friendAddedAt = values.friendAddedAt();
        this.friendTrustedAt = values.friendTrustedAt();
        this.friendQualifiedAt = values.friendQualifiedAt();
        this.tags.clear();
        this.tags.addAll(values.tags());
        this.updatedAt = Instant.now();
    }

    public void markContactCompletionPending() {
        this.contactCompletionStatus = "PENDING";
        this.updatedAt = Instant.now();
    }

    private static boolean hasContact(String phone, String wechatId, String qqId) {
        return hasText(phone) || hasText(wechatId) || hasText(qqId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public void markContact(Instant contactedAt, Instant nextFollowUpAt, String status, String intention, String verification) {
        this.lastContactAt = contactedAt;
        this.nextFollowUpAt = nextFollowUpAt;
        if (status != null) this.currentStatus = status;
        if (intention != null) this.intentionLevel = intention;
        if (verification != null) this.verificationStatus = verification;
        this.updatedAt = Instant.now();
    }

    public void applyTimelineMetadata(Instant nextFollowUpAt, String status, String intention, String verification) {
        this.nextFollowUpAt = nextFollowUpAt;
        if (status != null) this.currentStatus = status;
        if (intention != null) this.intentionLevel = intention;
        if (verification != null) this.verificationStatus = verification;
        this.updatedAt = Instant.now();
    }

    public void replaceTags(Set<TagEntity> replacement) {
        this.tags.clear();
        this.tags.addAll(replacement);
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.archivedAt = Instant.now();
        this.currentStatus = "归档";
        this.nextFollowUpAt = null;
        this.updatedAt = this.archivedAt;
    }

    public void reviewLead(LeadReviewStatus status, String reason, UUID reviewerMembershipId) {
        this.leadReviewStatus = status;
        this.leadReviewReason = reason;
        this.leadReviewedByMembershipId = reviewerMembershipId;
        this.leadReviewedAt = Instant.now();
        this.updatedAt = this.leadReviewedAt;
    }

    public void transferOwnership(UUID ownerMembershipId, UUID teamId) {
        this.ownerMembershipId = ownerMembershipId;
        this.teamId = teamId;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getOwnerMembershipId() { return ownerMembershipId; }
    public String getOwnerDisplayName() { return ownerDisplayName; }
    public UUID getCreatedByMembershipId() { return createdByMembershipId; }
    public String getCreatorDisplayName() { return creatorDisplayName; }
    public UUID getTeamId() { return teamId; }
    public CustomerVisibility getVisibility() { return visibility; }
    public String getFullName() { return fullName; }
    public String getNickname() { return nickname; }
    public String getPhone() { return phone; }
    public String getWechatId() { return wechatId; }
    public String getQqId() { return qqId; }
    public String getSchool() { return school; }
    public String getMajor() { return major; }
    public String getGrade() { return grade; }
    public String getCity() { return city; }
    public String getHighSchool() { return highSchool; }
    public String getSourceLocation() { return sourceLocation; }
    public String getIdentityType() { return identityType; }
    public Set<String> getFocusDirections() { return Set.copyOf(focusDirections); }
    public String getDemandSummary() { return demandSummary; }
    public String getIntentionLevel() { return intentionLevel; }
    public String getUrgencyLevel() { return urgencyLevel; }
    public String getVerificationStatus() { return verificationStatus; }
    public UUID getSourceDictionaryId() { return sourceDictionaryId; }
    public String getSourceChannelLegacy() { return sourceChannelLegacy; }
    public String getContactCompletionStatus() { return contactCompletionStatus; }
    public String getCurrentStatus() { return currentStatus; }
    public String getOwnerNotes() { return ownerNotes; }
    public Instant getLastContactAt() { return lastContactAt; }
    public Instant getNextFollowUpAt() { return nextFollowUpAt; }
    public Instant getFriendAddedAt() { return friendAddedAt; }
    public Instant getFriendTrustedAt() { return friendTrustedAt; }
    public Instant getFriendQualifiedAt() { return friendQualifiedAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public LeadReviewStatus getLeadReviewStatus() { return leadReviewStatus; }
    public String getLeadReviewReason() { return leadReviewReason; }
    public UUID getLeadReviewedByMembershipId() { return leadReviewedByMembershipId; }
    public String getLeadReviewerDisplayName() { return leadReviewerDisplayName; }
    public Instant getLeadReviewedAt() { return leadReviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    public Set<TagEntity> getTags() { return Set.copyOf(tags); }

    public record CustomerValues(
            UUID ownerMembershipId, UUID teamId, CustomerVisibility visibility, String fullName, String nickname,
            String phone, String normalizedPhone, String wechatId, String normalizedWechatId, String qqId,
            String normalizedQqId, String school, String major, String grade, String city,
            String highSchool, String sourceLocation, String identityType,
            Set<String> focusDirections, String demandSummary, String intentionLevel, String urgencyLevel,
            String verificationStatus, UUID sourceDictionaryId, String sourceChannelLegacy, String currentStatus,
            String ownerNotes, Instant nextFollowUpAt, Instant friendAddedAt, Instant friendTrustedAt,
            Instant friendQualifiedAt, Set<TagEntity> tags
    ) {}
}
