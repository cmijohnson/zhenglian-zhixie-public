package com.yingge.cloud.task.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkTaskRepository extends JpaRepository<WorkTaskEntity, UUID> {
    List<WorkTaskEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
    Optional<WorkTaskEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from WorkTaskEntity t where t.id=:id and t.organizationId=:organizationId")
    Optional<WorkTaskEntity> findForUpdate(UUID id, UUID organizationId);
}
