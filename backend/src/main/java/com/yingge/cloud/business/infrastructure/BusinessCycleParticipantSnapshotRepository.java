package com.yingge.cloud.business.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BusinessCycleParticipantSnapshotRepository
        extends JpaRepository<BusinessCycleParticipantSnapshotEntity, UUID> {
    List<BusinessCycleParticipantSnapshotEntity> findByBusinessCycleIdAndSnapshotVersionOrderByRoleTypeAscDisplayNameSnapshotAsc(
            UUID businessCycleId, int snapshotVersion);
}
