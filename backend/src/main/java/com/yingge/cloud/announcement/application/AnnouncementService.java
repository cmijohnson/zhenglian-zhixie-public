package com.yingge.cloud.announcement.application;

import com.yingge.cloud.announcement.api.AnnouncementDtos;
import com.yingge.cloud.announcement.domain.AnnouncementPriority;
import com.yingge.cloud.announcement.domain.AnnouncementStatus;
import com.yingge.cloud.announcement.infrastructure.AnnouncementEntity;
import com.yingge.cloud.announcement.infrastructure.AnnouncementRepository;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class AnnouncementService {
    private final AnnouncementRepository announcements;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;
    private final EntityManager entityManager;

    public AnnouncementService(AnnouncementRepository announcements, CurrentActorProvider actors,
                               PermissionService permissions, AuditService audit, EntityManager entityManager) {
        this.announcements = announcements;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<AnnouncementDtos.Response> list(boolean includeArchived) {
        var actor = actors.require();
        if (includeArchived && !actor.canPublishAnnouncements()) {
            throw ApiException.forbidden("只有管理员或团队长可以查看归档公告");
        }
        List<AnnouncementEntity> rows = includeArchived
                ? (actor.isAdmin() ? announcements.findByOrganizationId(actor.organizationId())
                    : announcements.findPublishedOrAuthored(actor.organizationId(), AnnouncementStatus.PUBLISHED,
                        actor.membershipId()))
                : announcements.findByOrganizationIdAndStatus(actor.organizationId(), AnnouncementStatus.PUBLISHED);
        return rows.stream().sorted(Comparator
                        .comparing(AnnouncementEntity::isPinned).reversed()
                        .thenComparing(AnnouncementEntity::getPriority, Comparator.reverseOrder())
                        .thenComparing(AnnouncementEntity::getPublishedAt, Comparator.reverseOrder()))
                .limit(200).map(this::response).toList();
    }

    @Transactional
    public AnnouncementDtos.Response create(AnnouncementDtos.UpsertRequest body) {
        var actor = actors.require();
        requirePublisher(actor);
        var saved = announcements.saveAndFlush(new AnnouncementEntity(
                actor.organizationId(), actor.membershipId(), required(body.title()), required(body.content()),
                body.priority(), body.pinned(), body.showOnLeadershipScreen()));
        entityManager.refresh(saved);
        audit.record(actor, "ANNOUNCEMENT_PUBLISHED", "ANNOUNCEMENT", saved.getId().toString(), "SUCCESS",
                java.util.Map.of("priority", body.priority().name(), "pinned", body.pinned(),
                        "showOnLeadershipScreen", body.showOnLeadershipScreen()));
        return response(saved);
    }

    @Transactional
    public AnnouncementDtos.Response update(UUID id, AnnouncementDtos.UpsertRequest body) {
        var actor = actors.require();
        requirePublisher(actor);
        AnnouncementEntity row = require(id, actor.organizationId());
        requireOwnerOrAdmin(row, actor);
        if (row.getStatus() == AnnouncementStatus.ARCHIVED) {
            throw ApiException.conflict("ANNOUNCEMENT_ARCHIVED", "已归档公告不能修改");
        }
        row.update(required(body.title()), required(body.content()), body.priority(), body.pinned(),
                body.showOnLeadershipScreen());
        audit.record(actor, "ANNOUNCEMENT_UPDATED", "ANNOUNCEMENT", id.toString(), "SUCCESS", null);
        return response(row);
    }

    @Transactional
    public void archive(UUID id) {
        var actor = actors.require();
        requirePublisher(actor);
        AnnouncementEntity row = require(id, actor.organizationId());
        requireOwnerOrAdmin(row, actor);
        if (row.getStatus() != AnnouncementStatus.ARCHIVED) {
            row.archive();
            audit.record(actor, "ANNOUNCEMENT_ARCHIVED", "ANNOUNCEMENT", id.toString(), "SUCCESS", null);
        }
    }

    private AnnouncementEntity require(UUID id, UUID organizationId) {
        return announcements.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> ApiException.notFound("ANNOUNCEMENT_NOT_FOUND", "公告不存在"));
    }

    private void requirePublisher(com.yingge.cloud.permission.domain.CurrentActor actor) {
        if (!actor.canPublishAnnouncements()) {
            throw ApiException.forbidden("只有管理员或团队长可以发布公告");
        }
    }

    private void requireOwnerOrAdmin(AnnouncementEntity row,
                                     com.yingge.cloud.permission.domain.CurrentActor actor) {
        if (!actor.isAdmin() && !row.getAuthorMembershipId().equals(actor.membershipId())) {
            throw ApiException.forbidden("团队长只能修改或归档自己发布的公告");
        }
    }

    private String required(String value) {
        String normalized = TextNormalizer.optional(value);
        if (normalized == null) throw ApiException.badRequest("ANNOUNCEMENT_CONTENT_REQUIRED", "公告内容不能为空");
        return normalized;
    }

    private AnnouncementDtos.Response response(AnnouncementEntity row) {
        return new AnnouncementDtos.Response(
                row.getId(), row.getAuthorMembershipId(), row.getAuthorDisplayName(), row.getTitle(), row.getContent(),
                row.getPriority(), row.isPinned(), row.isShowOnLeadershipScreen(), row.getStatus(),
                row.getPublishedAt(), row.getArchivedAt(),
                row.getUpdatedAt(), row.getVersion());
    }
}
