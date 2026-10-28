package com.yingge.cloud.tag.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TagRepository extends JpaRepository<TagEntity, UUID> {
    List<TagEntity> findByOrganizationIdAndActiveTrueOrderByCategoryAscNameAsc(UUID organizationId);
    List<TagEntity> findByOrganizationIdAndActiveTrueAndIdIn(UUID organizationId, Collection<UUID> ids);
    Optional<TagEntity> findByOrganizationIdAndNameIgnoreCase(UUID organizationId, String name);
}
