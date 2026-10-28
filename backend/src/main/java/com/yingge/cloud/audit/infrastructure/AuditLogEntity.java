package com.yingge.cloud.audit.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLogEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id")
    private UUID organizationId;
    @Column(name = "actor_membership_id")
    private UUID actorMembershipId;
    @Column(nullable = false)
    private String action;
    @Column(name = "entity_type")
    private String entityType;
    @Column(name = "entity_id")
    private String entityId;
    @Column(nullable = false)
    private String outcome;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;
    @Column(name = "trace_id")
    private String traceId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(UUID organizationId, UUID actorMembershipId, String action, String entityType,
                          String entityId, String outcome, Map<String, Object> details, String traceId) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.actorMembershipId = actorMembershipId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.outcome = outcome;
        this.details = details == null ? Map.of() : Map.copyOf(details);
        this.traceId = traceId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getOutcome() { return outcome; }
    public Map<String, Object> getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}

