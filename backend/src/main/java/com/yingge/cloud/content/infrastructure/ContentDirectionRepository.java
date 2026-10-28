package com.yingge.cloud.content.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentDirectionRepository extends JpaRepository<ContentDirectionEntity, UUID> {
    List<ContentDirectionEntity> findByOrganizationIdOrderByActiveDescCreatedAtDesc(UUID organizationId);
    List<ContentDirectionEntity> findByOrganizationIdAndActiveTrueOrderByCreatedAtDesc(UUID organizationId);
    Optional<ContentDirectionEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
