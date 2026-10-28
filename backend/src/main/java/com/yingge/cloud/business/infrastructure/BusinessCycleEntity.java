package com.yingge.cloud.business.infrastructure;

import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.business.domain.BusinessCycleType;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "customer_business_cycles")
public class BusinessCycleEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", insertable = false, updatable = false)
    private CustomerEntity customer;
    @Column(name = "cycle_number", nullable = false)
    private int cycleNumber;
    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_type", nullable = false)
    private BusinessCycleType cycleType;
    @Column(nullable = false)
    private String title;
    private String summary;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BusinessCycleStatus status;
    @Column(name = "cross_sale", nullable = false)
    private boolean crossSale;
    @Column(name = "current_snapshot_version", nullable = false)
    private int currentSnapshotVersion;
    @Column(name = "suggested_primary_membership_id")
    private UUID suggestedPrimaryMembershipId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "suggested_assistant_membership_ids", nullable = false, columnDefinition = "jsonb")
    private List<UUID> suggestedAssistantMembershipIds;
    @Column(name = "lock_request_reason")
    private String lockRequestReason;
    @Column(name = "lock_requested_by_membership_id")
    private UUID lockRequestedByMembershipId;
    @Column(name = "lock_requested_at")
    private Instant lockRequestedAt;
    @Column(name = "quote_type")
    private String quoteType;
    @Column(name = "quoted_at")
    private Instant quotedAt;
    @Column(name = "quote_confirmed_by_membership_id")
    private UUID quoteConfirmedByMembershipId;
    @Column(name = "participant_locked_at")
    private Instant participantLockedAt;
    @Column(name = "won_declaration_fact")
    private String wonDeclarationFact;
    @Column(name = "won_declared_by_membership_id")
    private UUID wonDeclaredByMembershipId;
    @Column(name = "won_declared_at")
    private Instant wonDeclaredAt;
    @Column(name = "won_confirmed_by_membership_id")
    private UUID wonConfirmedByMembershipId;
    @Column(name = "won_confirmed_at")
    private Instant wonConfirmedAt;
    @Column(name = "emergency_exception_reason")
    private String emergencyExceptionReason;
    @Column(name = "competitor_name")
    private String competitorName;
    @Column(name = "competitor_fact")
    private String competitorFact;
    @Column(name = "status_marked_at", nullable = false)
    private Instant statusMarkedAt;
    @Column(name = "idempotency_key_hash", nullable = false)
    private String idempotencyKeyHash;
    @Column(name = "created_by_membership_id", nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected BusinessCycleEntity() {
    }

    public BusinessCycleEntity(UUID organizationId, UUID customerId, int cycleNumber, String title,
                               String summary, String idempotencyKeyHash, UUID createdByMembershipId) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.customerId = customerId;
        this.cycleNumber = cycleNumber;
        this.cycleType = cycleNumber == 1 ? BusinessCycleType.INITIAL : BusinessCycleType.REPURCHASE;
        this.title = title;
        this.summary = summary;
        this.status = BusinessCycleStatus.IN_PROGRESS;
        this.suggestedAssistantMembershipIds = List.of();
        this.idempotencyKeyHash = idempotencyKeyHash;
        this.createdByMembershipId = createdByMembershipId;
        this.statusMarkedAt = Instant.now();
        this.createdAt = this.statusMarkedAt;
        this.updatedAt = this.statusMarkedAt;
    }

    public void requestParticipantLock(String reason, UUID primaryMembershipId,
                                       List<UUID> assistantMembershipIds, UUID requestedBy) {
        this.status = BusinessCycleStatus.LOCK_PENDING;
        this.lockRequestReason = reason;
        this.suggestedPrimaryMembershipId = primaryMembershipId;
        this.suggestedAssistantMembershipIds = List.copyOf(assistantMembershipIds);
        this.lockRequestedByMembershipId = requestedBy;
        this.lockRequestedAt = Instant.now();
        touchStatus();
    }

    public int confirmFormalQuote(String quoteType, Instant quotedAt, boolean crossSale,
                                  String emergencyExceptionReason, UUID confirmedBy) {
        this.status = BusinessCycleStatus.FORMALLY_QUOTED;
        this.quoteType = quoteType;
        this.quotedAt = quotedAt;
        this.crossSale = crossSale;
        this.currentSnapshotVersion += 1;
        this.quoteConfirmedByMembershipId = confirmedBy;
        this.participantLockedAt = Instant.now();
        this.emergencyExceptionReason = emergencyExceptionReason;
        touchStatus();
        return this.currentSnapshotVersion;
    }

    public void rejectParticipantLock() {
        this.status = BusinessCycleStatus.IN_PROGRESS;
        this.suggestedPrimaryMembershipId = null;
        this.suggestedAssistantMembershipIds = List.of();
        this.lockRequestReason = null;
        this.lockRequestedByMembershipId = null;
        this.lockRequestedAt = null;
        touchStatus();
    }

    public void declareWon(String fact, UUID declaredBy) {
        this.status = BusinessCycleStatus.WON_PENDING;
        this.wonDeclarationFact = fact;
        this.wonDeclaredByMembershipId = declaredBy;
        this.wonDeclaredAt = Instant.now();
        touchStatus();
    }

    public void confirmWon(UUID confirmedBy) {
        this.status = BusinessCycleStatus.WON;
        this.wonConfirmedByMembershipId = confirmedBy;
        this.wonConfirmedAt = Instant.now();
        touchStatus();
    }

    public int reviseParticipantSnapshot(boolean crossSale) {
        this.crossSale = crossSale;
        this.currentSnapshotVersion += 1;
        this.updatedAt = Instant.now();
        return this.currentSnapshotVersion;
    }

    public void markLost(String competitorName, String competitorFact) {
        this.status = BusinessCycleStatus.LOST_TO_COMPETITOR;
        this.competitorName = competitorName;
        this.competitorFact = competitorFact;
        touchStatus();
    }

    public void cancel() {
        this.status = BusinessCycleStatus.CANCELLED;
        touchStatus();
    }

    public void correctStatus(BusinessCycleStatus targetStatus) {
        this.status = targetStatus;
        if (targetStatus != BusinessCycleStatus.LOST_TO_COMPETITOR) {
            this.competitorName = null;
            this.competitorFact = null;
        }
        touchStatus();
    }

    private void touchStatus() {
        this.statusMarkedAt = Instant.now();
        this.updatedAt = this.statusMarkedAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getCustomerId() { return customerId; }
    public CustomerEntity getCustomer() { return customer; }
    public int getCycleNumber() { return cycleNumber; }
    public BusinessCycleType getCycleType() { return cycleType; }
    public String getTitle() { return title; }
    public String getSummary() { return summary; }
    public BusinessCycleStatus getStatus() { return status; }
    public boolean isCrossSale() { return crossSale; }
    public int getCurrentSnapshotVersion() { return currentSnapshotVersion; }
    public UUID getSuggestedPrimaryMembershipId() { return suggestedPrimaryMembershipId; }
    public List<UUID> getSuggestedAssistantMembershipIds() {
        return suggestedAssistantMembershipIds == null ? List.of() : List.copyOf(suggestedAssistantMembershipIds);
    }
    public String getLockRequestReason() { return lockRequestReason; }
    public UUID getLockRequestedByMembershipId() { return lockRequestedByMembershipId; }
    public Instant getLockRequestedAt() { return lockRequestedAt; }
    public String getQuoteType() { return quoteType; }
    public Instant getQuotedAt() { return quotedAt; }
    public UUID getQuoteConfirmedByMembershipId() { return quoteConfirmedByMembershipId; }
    public Instant getParticipantLockedAt() { return participantLockedAt; }
    public String getWonDeclarationFact() { return wonDeclarationFact; }
    public UUID getWonDeclaredByMembershipId() { return wonDeclaredByMembershipId; }
    public Instant getWonDeclaredAt() { return wonDeclaredAt; }
    public UUID getWonConfirmedByMembershipId() { return wonConfirmedByMembershipId; }
    public Instant getWonConfirmedAt() { return wonConfirmedAt; }
    public String getEmergencyExceptionReason() { return emergencyExceptionReason; }
    public String getCompetitorName() { return competitorName; }
    public String getCompetitorFact() { return competitorFact; }
    public Instant getStatusMarkedAt() { return statusMarkedAt; }
    public String getIdempotencyKeyHash() { return idempotencyKeyHash; }
    public UUID getCreatedByMembershipId() { return createdByMembershipId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
