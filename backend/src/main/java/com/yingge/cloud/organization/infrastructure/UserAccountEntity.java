package com.yingge.cloud.organization.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_accounts")
public class UserAccountEntity {
    @Id
    private UUID id;
    @Column(nullable = false, unique = true)
    private String username;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(name = "display_name", nullable = false)
    private String displayName;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "password_change_required", nullable = false)
    private boolean passwordChangeRequired;
    @Column(name = "last_login_at")
    private Instant lastLoginAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "auth_version", nullable = false)
    private long authVersion;

    protected UserAccountEntity() {
    }

    public UserAccountEntity(UUID id, String username, String passwordHash, String displayName, Instant now) {
        this(id, username, passwordHash, displayName, true, now);
    }

    public UserAccountEntity(UUID id, String username, String passwordHash, String displayName,
                             boolean passwordChangeRequired, Instant now) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.enabled = true;
        this.passwordChangeRequired = passwordChangeRequired;
        this.createdAt = now;
        this.updatedAt = now;
        this.authVersion = 0;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public String getDisplayName() { return displayName; }
    public boolean isEnabled() { return enabled; }
    public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
    public long getAuthVersion() { return authVersion; }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public void changePassword(String passwordHash, Instant now) {
        this.passwordHash = passwordHash;
        this.passwordChangeRequired = false;
        this.authVersion += 1;
        this.updatedAt = now;
    }
}
