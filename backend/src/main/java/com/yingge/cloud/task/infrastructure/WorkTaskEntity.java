package com.yingge.cloud.task.infrastructure;

import com.yingge.cloud.task.domain.TaskDistributionType;
import com.yingge.cloud.task.domain.WorkTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "work_tasks")
public class WorkTaskEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String description;
    @Enumerated(EnumType.STRING)
    @Column(name = "distribution_type", nullable = false)
    private TaskDistributionType distributionType;
    @Column(name = "start_at", nullable = false)
    private Instant startAt;
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;
    @Column(name = "max_claimants")
    private Integer maxClaimants;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkTaskStatus status;
    @Column(name = "created_by_membership_id", nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected WorkTaskEntity() {}

    public WorkTaskEntity(UUID organizationId, String title, String description,
                          TaskDistributionType distributionType, Instant startAt, Instant dueAt,
                          Integer maxClaimants, UUID createdByMembershipId) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.title = title;
        this.description = description;
        this.distributionType = distributionType;
        this.startAt = startAt;
        this.dueAt = dueAt;
        this.maxClaimants = maxClaimants;
        this.status = WorkTaskStatus.PUBLISHED;
        this.createdByMembershipId = createdByMembershipId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void close() {
        this.status = WorkTaskStatus.CLOSED;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public TaskDistributionType getDistributionType() { return distributionType; }
    public Instant getStartAt() { return startAt; }
    public Instant getDueAt() { return dueAt; }
    public Integer getMaxClaimants() { return maxClaimants; }
    public WorkTaskStatus getStatus() { return status; }
    public UUID getCreatedByMembershipId() { return createdByMembershipId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
