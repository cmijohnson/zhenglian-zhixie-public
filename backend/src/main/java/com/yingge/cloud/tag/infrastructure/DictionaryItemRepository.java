package com.yingge.cloud.tag.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DictionaryItemRepository extends JpaRepository<DictionaryItemEntity, UUID> {
    List<DictionaryItemEntity> findByOrganizationIdAndActiveTrueOrderByTypeAscSortOrderAsc(UUID organizationId);
    Optional<DictionaryItemEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    long countByOrganizationIdAndTypeAndActiveTrue(UUID organizationId, String type);
    boolean existsByOrganizationIdAndTypeAndActiveTrueAndLabelIgnoreCase(UUID organizationId, String type, String label);
}
