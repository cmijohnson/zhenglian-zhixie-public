package com.yingge.cloud.tag.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tags")
public class TagEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private String category;
    @Column(nullable = false)
    private String color;
    @Column(name = "is_system", nullable = false)
    private boolean system;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_by_membership_id")
    private UUID createdByMembershipId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TagEntity() {
    }

    public TagEntity(UUID organizationId, String name, String category, String color, UUID createdByMembershipId) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.name = name;
        this.category = category;
        this.color = color;
        this.system = false;
        this.active = true;
        this.createdByMembershipId = createdByMembershipId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getColor() { return color; }
    public boolean isSystem() { return system; }
    public boolean isActive() { return active; }
}

