package com.yingge.cloud.announcement.infrastructure;

import com.yingge.cloud.announcement.domain.AnnouncementStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<AnnouncementEntity, UUID> {
    List<AnnouncementEntity> findByOrganizationIdAndStatus(UUID organizationId, AnnouncementStatus status);
    List<AnnouncementEntity> findByOrganizationId(UUID organizationId);
    @Query("select a from AnnouncementEntity a where a.organizationId=:organizationId " +
            "and (a.status=:published or a.authorMembershipId=:authorMembershipId)")
    List<AnnouncementEntity> findPublishedOrAuthored(UUID organizationId, AnnouncementStatus published,
                                                      UUID authorMembershipId);
    Optional<AnnouncementEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
