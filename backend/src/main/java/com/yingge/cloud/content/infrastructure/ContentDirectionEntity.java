package com.yingge.cloud.content.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "content_directions")
public class ContentDirectionEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(nullable = false)
    private String title;
    private String description;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_by_membership_id", nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ContentDirectionEntity() {}

    public ContentDirectionEntity(UUID organizationId, UUID createdByMembershipId, String title, String description) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.createdByMembershipId = createdByMembershipId;
        this.title = title;
        this.description = description;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String title, String description, boolean active) {
        this.title = title;
        this.description = description;
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
