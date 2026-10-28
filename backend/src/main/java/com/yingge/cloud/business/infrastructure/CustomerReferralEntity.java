package com.yingge.cloud.business.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_referrals")
public class CustomerReferralEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "referrer_customer_id", nullable = false)
    private UUID referrerCustomerId;
    @Column(name = "referred_customer_id", nullable = false)
    private UUID referredCustomerId;
    @Column(name = "source_business_cycle_id")
    private UUID sourceBusinessCycleId;
    @Column(name = "relationship_note")
    private String relationshipNote;
    @Column(name = "created_by_membership_id", nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "idempotency_key_hash", nullable = false)
    private String idempotencyKeyHash;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CustomerReferralEntity() {
    }

    public CustomerReferralEntity(UUID organizationId, UUID referrerCustomerId, UUID referredCustomerId,
                                  UUID sourceBusinessCycleId, String relationshipNote,
                                  UUID createdByMembershipId, String idempotencyKeyHash) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.referrerCustomerId = referrerCustomerId;
        this.referredCustomerId = referredCustomerId;
        this.sourceBusinessCycleId = sourceBusinessCycleId;
        this.relationshipNote = relationshipNote;
        this.createdByMembershipId = createdByMembershipId;
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getReferrerCustomerId() { return referrerCustomerId; }
    public UUID getReferredCustomerId() { return referredCustomerId; }
    public UUID getSourceBusinessCycleId() { return sourceBusinessCycleId; }
    public String getRelationshipNote() { return relationshipNote; }
    public UUID getCreatedByMembershipId() { return createdByMembershipId; }
    public Instant getCreatedAt() { return createdAt; }
}
