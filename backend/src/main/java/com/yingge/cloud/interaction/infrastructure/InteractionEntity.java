package com.yingge.cloud.interaction.infrastructure;

import com.yingge.cloud.market.domain.InteractionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "interactions")
public class InteractionEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "contacted_at", nullable = false)
    private Instant contactedAt;
    @Column(nullable = false)
    private String channel;
    @Column(nullable = false)
    private String summary;
    private String details;
    private String outcome;
    @Column(name = "verification_todo")
    private String verificationTodo;
    @Column(name = "next_follow_up_at")
    private Instant nextFollowUpAt;
    @Enumerated(EnumType.STRING)
    @Column(name = "interaction_type", nullable = false)
    private InteractionType interactionType;
    @Column(name = "content_fingerprint")
    private String contentFingerprint;
    @Column(name = "deadline_effect", nullable = false)
    private String deadlineEffect;
    @Column(name = "deadline_effect_reason")
    private String deadlineEffectReason;
    @Column(name = "created_by_membership_id", nullable = false)
    private UUID createdByMembershipId;
    @Formula("(select account.display_name from memberships member join user_accounts account on account.id=member.user_id where member.id=created_by_membership_id)")
    private String createdByDisplayName;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected InteractionEntity() {
    }

    public InteractionEntity(UUID organizationId, UUID customerId, Instant contactedAt, String channel,
                             String summary, String details, String outcome, String verificationTodo,
                             Instant nextFollowUpAt, UUID createdByMembershipId) {
        this(organizationId, customerId, contactedAt, channel, summary, details, outcome, verificationTodo,
                nextFollowUpAt, createdByMembershipId, InteractionType.CUSTOMER_REPLY, null);
    }

    public InteractionEntity(UUID organizationId, UUID customerId, Instant contactedAt, String channel,
                             String summary, String details, String outcome, String verificationTodo,
                             Instant nextFollowUpAt, UUID createdByMembershipId,
                             InteractionType interactionType, String contentFingerprint) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.customerId = customerId;
        this.contactedAt = contactedAt;
        this.channel = channel;
        this.summary = summary;
        this.details = details;
        this.outcome = outcome;
        this.verificationTodo = verificationTodo;
        this.nextFollowUpAt = nextFollowUpAt;
        this.interactionType = interactionType;
        this.contentFingerprint = contentFingerprint;
        this.deadlineEffect = "IGNORED";
        this.deadlineEffectReason = "PENDING_EVALUATION";
        this.createdByMembershipId = createdByMembershipId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public Instant getContactedAt() { return contactedAt; }
    public String getChannel() { return channel; }
    public String getSummary() { return summary; }
    public String getDetails() { return details; }
    public String getOutcome() { return outcome; }
    public String getVerificationTodo() { return verificationTodo; }
    public Instant getNextFollowUpAt() { return nextFollowUpAt; }
    public InteractionType getInteractionType() { return interactionType; }
    public String getContentFingerprint() { return contentFingerprint; }
    public String getDeadlineEffect() { return deadlineEffect; }
    public String getDeadlineEffectReason() { return deadlineEffectReason; }
    public UUID getCreatedByMembershipId() { return createdByMembershipId; }
    public String getCreatedByDisplayName() { return createdByDisplayName; }
    public Instant getCreatedAt() { return createdAt; }

    public void recordDeadlineEvaluation(boolean applied, String reason) {
        this.deadlineEffect = applied ? "APPLIED" : "IGNORED";
        this.deadlineEffectReason = reason;
    }
}
