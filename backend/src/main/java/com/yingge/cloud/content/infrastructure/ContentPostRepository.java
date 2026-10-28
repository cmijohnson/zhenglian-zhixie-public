package com.yingge.cloud.content.infrastructure;

import com.yingge.cloud.content.domain.ContentPlatform;
import com.yingge.cloud.content.domain.ContentReviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentPostRepository extends JpaRepository<ContentPostEntity, UUID>, JpaSpecificationExecutor<ContentPostEntity> {
    Optional<ContentPostEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    Page<ContentPostEntity> findByOrganizationId(UUID organizationId, Pageable pageable);
    List<ContentPostEntity> findByOrganizationIdAndMembershipIdOrderByPublishDateDescCreatedAtDesc(
            UUID organizationId, UUID membershipId);
    List<ContentPostEntity> findByOrganizationIdAndReviewStatusOrderByCreatedAtAsc(
            UUID organizationId, ContentReviewStatus status);
    List<ContentPostEntity> findByOrganizationIdAndReviewStatusAndMembershipIdInOrderByCreatedAtAsc(
            UUID organizationId, ContentReviewStatus status, java.util.Collection<UUID> membershipIds);
    long countByOrganizationIdAndMembershipIdAndPlatformAndReviewStatusAndPublishDateBetween(
            UUID organizationId, UUID membershipId, ContentPlatform platform, ContentReviewStatus status,
            LocalDate startDate, LocalDate endDate);
}
