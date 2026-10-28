package com.yingge.cloud.customer.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "customer_access_grants")
public class CustomerAccessGrantEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "grantee_membership_id")
    private UUID granteeMembershipId;
    @Column(name = "grantee_team_id")
    private UUID granteeTeamId;
    @Column(name = "can_edit", nullable = false)
    private boolean canEdit;

    protected CustomerAccessGrantEntity() {
    }

    public UUID getCustomerId() { return customerId; }
    public UUID getGranteeMembershipId() { return granteeMembershipId; }
    public UUID getGranteeTeamId() { return granteeTeamId; }
    public boolean isCanEdit() { return canEdit; }
}

