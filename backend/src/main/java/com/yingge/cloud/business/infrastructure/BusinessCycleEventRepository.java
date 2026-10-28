package com.yingge.cloud.business.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BusinessCycleEventRepository extends JpaRepository<BusinessCycleEventEntity, UUID> {
    List<BusinessCycleEventEntity> findByBusinessCycleIdOrderByCreatedAtAscIdAsc(UUID businessCycleId);
}
