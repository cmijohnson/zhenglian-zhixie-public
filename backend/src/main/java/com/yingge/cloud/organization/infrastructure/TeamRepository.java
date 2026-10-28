package com.yingge.cloud.organization.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TeamRepository extends JpaRepository<TeamEntity, UUID> {
    List<TeamEntity> findByOrganizationIdAndActiveTrueOrderByNameAsc(UUID organizationId);
    Optional<TeamEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select team from TeamEntity team where team.id=:id and team.organizationId=:organizationId")
    Optional<TeamEntity> findForUpdate(@Param("id") UUID id, @Param("organizationId") UUID organizationId);
}
