package com.yingge.cloud.permission.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "team_members")
@IdClass(TeamMembershipScopeEntity.Key.class)
public class TeamMembershipScopeEntity {
    @Id
    @Column(name = "team_id")
    private UUID teamId;
    @Id
    @Column(name = "membership_id")
    private UUID membershipId;
    @Column(name = "is_manager", nullable = false)
    private boolean manager;

    protected TeamMembershipScopeEntity() {
    }

    public record Key(UUID teamId, UUID membershipId) implements Serializable {
    }
}
