package com.yingge.cloud.business.infrastructure;

import com.yingge.cloud.business.domain.BusinessParticipantRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customer_business_participant_snapshots")
public class BusinessCycleParticipantSnapshotEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "business_cycle_id", nullable = false)
    private UUID businessCycleId;
    @Column(name = "snapshot_version", nullable = false)
    private int snapshotVersion;
    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;
    @Enumerated(EnumType.STRING)
    @Column(name = "role_type", nullable = false)
    private BusinessParticipantRole roleType;
    @Column(name = "display_name_snapshot", nullable = false)
    private String displayNameSnapshot;
    @Column(name = "confirmed_by_membership_id", nullable = false)
    private UUID confirmedByMembershipId;
    @Column(name = "confirmation_reason")
    private String confirmationReason;
    @Column(name = "confirmed_at", nullable = false)
    private Instant confirmedAt;

    protected BusinessCycleParticipantSnapshotEntity() {
    }

    public BusinessCycleParticipantSnapshotEntity(UUID organizationId, UUID businessCycleId, int snapshotVersion,
                                                  UUID membershipId, BusinessParticipantRole roleType,
                                                  String displayNameSnapshot, UUID confirmedByMembershipId,
                                                  String confirmationReason) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.businessCycleId = businessCycleId;
        this.snapshotVersion = snapshotVersion;
        this.membershipId = membershipId;
        this.roleType = roleType;
        this.displayNameSnapshot = displayNameSnapshot;
        this.confirmedByMembershipId = confirmedByMembershipId;
        this.confirmationReason = confirmationReason;
        this.confirmedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getBusinessCycleId() { return businessCycleId; }
    public int getSnapshotVersion() { return snapshotVersion; }
    public UUID getMembershipId() { return membershipId; }
    public BusinessParticipantRole getRoleType() { return roleType; }
    public String getDisplayNameSnapshot() { return displayNameSnapshot; }
    public UUID getConfirmedByMembershipId() { return confirmedByMembershipId; }
    public String getConfirmationReason() { return confirmationReason; }
    public Instant getConfirmedAt() { return confirmedAt; }
}
