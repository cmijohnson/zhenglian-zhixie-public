package com.yingge.cloud.tag.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dictionary_items")
public class DictionaryItemEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(nullable = false)
    private String type;
    @Column(nullable = false)
    private String code;
    @Column(nullable = false)
    private String label;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DictionaryItemEntity() {
    }

    public DictionaryItemEntity(UUID organizationId, String type, String code, String label, int sortOrder) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.type = type;
        this.code = code;
        this.label = label;
        this.active = true;
        this.sortOrder = sortOrder;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() { return id; }
    public String getType() { return type; }
    public String getCode() { return code; }
    public String getLabel() { return label; }
    public boolean isActive() { return active; }
    public int getSortOrder() { return sortOrder; }
}

