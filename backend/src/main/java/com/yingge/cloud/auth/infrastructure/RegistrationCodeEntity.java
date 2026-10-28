package com.yingge.cloud.auth.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "registration_codes")
public class RegistrationCodeEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;
    @Column(name = "code_hint", nullable = false, length = 40)
    private String codeHint;
    @Column(name = "max_uses", nullable = false)
    private int maxUses;
    @Column(name = "used_count", nullable = false)
    private int usedCount;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_by_membership_id", nullable = false)
    private UUID createdByMembershipId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "disabled_at")
    private Instant disabledAt;
    @Column(name = "last_used_at")
    private Instant lastUsedAt;
    @Version
    private long version;

    protected RegistrationCodeEntity() {
    }

    public RegistrationCodeEntity(UUID organizationId, String codeHash, String codeHint, int maxUses,
                                  Instant expiresAt, UUID createdByMembershipId, Instant now) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.codeHash = codeHash;
        this.codeHint = codeHint;
        this.maxUses = maxUses;
        this.usedCount = 0;
        this.expiresAt = expiresAt;
        this.active = true;
        this.createdByMembershipId = createdByMembershipId;
        this.createdAt = now;
    }

    public void consume(Instant now) {
        this.usedCount++;
        this.lastUsedAt = now;
        if (this.usedCount >= this.maxUses) this.active = false;
    }

    public void disable(Instant now) {
        this.active = false;
        this.disabledAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getCodeHint() { return codeHint; }
    public int getMaxUses() { return maxUses; }
    public int getUsedCount() { return usedCount; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
    public UUID getCreatedByMembershipId() { return createdByMembershipId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getDisabledAt() { return disabledAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public long getVersion() { return version; }
}
