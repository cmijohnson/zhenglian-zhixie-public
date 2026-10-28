package com.yingge.cloud.business.infrastructure;

import com.yingge.cloud.business.domain.BusinessCycleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "customer_business_cycle_events")
public class BusinessCycleEventEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "business_cycle_id", nullable = false)
    private UUID businessCycleId;
    @Column(name = "actor_membership_id", nullable = false)
    private UUID actorMembershipId;
    @Column(nullable = false)
    private String action;
    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status")
    private BusinessCycleStatus previousStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "new_status")
    private BusinessCycleStatus newStatus;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected BusinessCycleEventEntity() {
    }

    public BusinessCycleEventEntity(UUID organizationId, UUID businessCycleId, UUID actorMembershipId,
                                    String action, BusinessCycleStatus previousStatus,
                                    BusinessCycleStatus newStatus, Map<String, Object> details) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.businessCycleId = businessCycleId;
        this.actorMembershipId = actorMembershipId;
        this.action = action;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBusinessCycleId() { return businessCycleId; }
    public UUID getActorMembershipId() { return actorMembershipId; }
    public String getAction() { return action; }
    public BusinessCycleStatus getPreviousStatus() { return previousStatus; }
    public BusinessCycleStatus getNewStatus() { return newStatus; }
    public Map<String, Object> getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
