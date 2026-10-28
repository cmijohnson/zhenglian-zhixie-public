package com.yingge.cloud.content.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.content.api.ContentDtos;
import com.yingge.cloud.content.domain.ContentPlatform;
import com.yingge.cloud.content.domain.ContentReviewStatus;
import com.yingge.cloud.content.infrastructure.ContentDirectionEntity;
import com.yingge.cloud.content.infrastructure.ContentDirectionRepository;
import com.yingge.cloud.content.infrastructure.ContentPostEntity;
import com.yingge.cloud.content.infrastructure.ContentPostRepository;
import com.yingge.cloud.content.infrastructure.ContentPostSpecifications;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.api.PageResponse;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ContentService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern HTTP_URL_PATTERN = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_SHARE_PUNCTUATION = Pattern.compile("[,，。；;！!）)】\\]]+$");

    private final ContentCampaignProperties campaign;
    private final ContentDirectionRepository directions;
    private final ContentPostRepository posts;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public ContentService(ContentCampaignProperties campaign, ContentDirectionRepository directions,
                          ContentPostRepository posts, CurrentActorProvider actors,
                          PermissionService permissions, AuditService audit, JdbcTemplate jdbc) {
        this.campaign = campaign;
        this.directions = directions;
        this.posts = posts;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ContentDtos.OverviewResponse overview() {
        CurrentActor actor = actors.require();
        var directionRows = directions.findByOrganizationIdOrderByActiveDescCreatedAtDesc(actor.organizationId());
        var directionMap = directionRows.stream().collect(Collectors.toMap(ContentDirectionEntity::getId,
                ContentDirectionEntity::getTitle));
        Map<UUID, String> names = memberNames(actor.organizationId());
        var own = posts.findByOrganizationIdAndMembershipIdOrderByPublishDateDescCreatedAtDesc(
                actor.organizationId(), actor.membershipId()).stream()
                .map(row -> response(row, names, directionMap)).toList();
        Set<UUID> reviewableMembers = actor.isAdmin() ? Set.of() : managedMemberIds(actor);
        var queue = actor.canReviewTeamOperations()
                ? (actor.isAdmin()
                    ? posts.findByOrganizationIdAndReviewStatusOrderByCreatedAtAsc(
                        actor.organizationId(), ContentReviewStatus.SUBMITTED)
                    : posts.findByOrganizationIdAndReviewStatusAndMembershipIdInOrderByCreatedAtAsc(
                        actor.organizationId(), ContentReviewStatus.SUBMITTED, reviewableMembers)).stream()
                    .map(row -> response(row, names, directionMap)).toList()
                : List.<ContentDtos.PostResponse>of();
        var progress = new ContentDtos.ProgressResponse(
                validCount(actor, ContentPlatform.XIAOHONGSHU), validCount(actor, ContentPlatform.DOUYIN));
        return new ContentDtos.OverviewResponse(
                campaignResponse(), directionRows.stream().map(this::directionResponse).toList(), progress,
                own, queue, actor.canReviewTeamOperations()
                        ? memberProgress(actor.organizationId(), actor.isAdmin() ? null : reviewableMembers) : List.of(),
                actor.isAdmin(), actor.canReviewTeamOperations());
    }

    @Transactional(readOnly = true)
    public PageResponse<ContentDtos.PostResponse> circle(int page, int size, String search,
                                                         UUID membershipId, ContentPlatform platform,
                                                         ContentReviewStatus reviewStatus, String sort) {
        CurrentActor actor = actors.require();
        Map<UUID, String> names = memberNames(actor.organizationId());
        Map<UUID, String> directionMap = directions.findByOrganizationIdOrderByActiveDescCreatedAtDesc(actor.organizationId())
                .stream().collect(Collectors.toMap(ContentDirectionEntity::getId, ContentDirectionEntity::getTitle));
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), circleSort(sort));
        return PageResponse.from(posts.findAll(
                        ContentPostSpecifications.circle(actor.organizationId(), search, membershipId,
                                platform, reviewStatus), pageable)
                .map(row -> response(row, names, directionMap)));
    }

    @Transactional(readOnly = true)
    public List<ContentDtos.SenderOption> circleSenders() {
        CurrentActor actor = actors.require();
        return jdbc.query("""
                select distinct m.id, u.display_name from content_posts cp
                join memberships m on m.id=cp.membership_id
                join user_accounts u on u.id=m.user_id
                where cp.organization_id=? order by u.display_name
                """, (row, index) -> new ContentDtos.SenderOption(
                row.getObject("id", UUID.class), row.getString("display_name")), actor.organizationId());
    }

    @Transactional
    public ContentDtos.DirectionResponse createDirection(ContentDtos.DirectionRequest body) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        String title = required(body.title(), "DIRECTION_TITLE_REQUIRED", "内容方向标题不能为空");
        var saved = directions.save(new ContentDirectionEntity(actor.organizationId(), actor.membershipId(),
                title, TextNormalizer.optional(body.description())));
        audit.record(actor, "CONTENT_DIRECTION_CREATED", "CONTENT_DIRECTION", saved.getId().toString(), "SUCCESS", null);
        return directionResponse(saved);
    }

    @Transactional
    public ContentDtos.DirectionResponse updateDirection(UUID id, ContentDtos.DirectionRequest body) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        var direction = requireDirection(id, actor.organizationId());
        direction.update(required(body.title(), "DIRECTION_TITLE_REQUIRED", "内容方向标题不能为空"),
                TextNormalizer.optional(body.description()), body.active() == null || body.active());
        audit.record(actor, "CONTENT_DIRECTION_UPDATED", "CONTENT_DIRECTION", id.toString(), "SUCCESS", null);
        return directionResponse(direction);
    }

    @Transactional
    public ContentDtos.PostResponse submit(ContentDtos.PostRequest body) {
        CurrentActor actor = actors.require();
        requireContributor(actor);
        validatePublishDate(body.publishDate());
        ContentDirectionEntity direction = requireDirection(body.directionId(), actor.organizationId());
        if (!direction.isActive()) throw ApiException.badRequest("DIRECTION_INACTIVE", "该内容方向已停用");
        if (!body.originalDeclared()) {
            throw ApiException.badRequest("ORIGINAL_DECLARATION_REQUIRED", "必须确认内容不是搬运内容");
        }
        String topic = required(body.topic(), "CONTENT_TOPIC_REQUIRED", "内容主题不能为空");
        String url = validUrl(body.contentUrl());
        try {
            var saved = posts.saveAndFlush(new ContentPostEntity(
                    actor.organizationId(), actor.membershipId(), body.platform(), body.publishDate(),
                    body.directionId(), topic, url, body.viewCount(), body.likeCount(), body.favoriteCount(), true));
            audit.record(actor, "CONTENT_POST_SUBMITTED", "CONTENT_POST", saved.getId().toString(), "SUCCESS",
                    Map.of("platform", body.platform().name(), "publishDate", body.publishDate().toString()));
            return response(saved, memberNames(actor.organizationId()), Map.of(direction.getId(), direction.getTitle()));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("DAILY_PLATFORM_LIMIT", "每天每个平台只能提交一条内容，可更新已提交记录的数据");
        }
    }

    @Transactional
    public ContentDtos.PostResponse updateMetrics(UUID id, ContentDtos.MetricsRequest body) {
        CurrentActor actor = actors.require();
        ContentPostEntity post = requirePost(id, actor.organizationId());
        if (!post.getMembershipId().equals(actor.membershipId()) && !actor.isAdmin()) {
            throw ApiException.forbidden("只能更新自己提交的内容数据");
        }
        post.updateMetrics(body.viewCount(), body.likeCount(), body.favoriteCount());
        audit.record(actor, "CONTENT_METRICS_UPDATED", "CONTENT_POST", id.toString(), "SUCCESS", null);
        var direction = requireDirection(post.getDirectionId(), actor.organizationId());
        return response(post, memberNames(actor.organizationId()), Map.of(direction.getId(), direction.getTitle()));
    }

    @Transactional
    public ContentDtos.PostResponse review(UUID id, ContentDtos.ReviewRequest body) {
        CurrentActor actor = actors.require();
        if (!actor.canReviewTeamOperations()) {
            throw ApiException.forbidden("只有管理员或团队长可以审核内容任务");
        }
        if (body.status() == ContentReviewStatus.SUBMITTED) {
            throw ApiException.badRequest("INVALID_REVIEW_STATUS", "审核结果只能是有效或无效");
        }
        String reason = TextNormalizer.optional(body.reason());
        if (body.status() == ContentReviewStatus.INVALID && reason == null) {
            throw ApiException.badRequest("REVIEW_REASON_REQUIRED", "判定无效时必须填写原因");
        }
        ContentPostEntity post = requirePost(id, actor.organizationId());
        if (!actor.isAdmin() && !managedMemberIds(actor).contains(post.getMembershipId())) {
            throw ApiException.forbidden("团队长只能审核本团队成员的内容任务");
        }
        post.review(body.status(), reason, actor.membershipId());
        audit.record(actor, "CONTENT_POST_REVIEWED", "CONTENT_POST", id.toString(), "SUCCESS",
                Map.of("status", body.status().name()));
        var direction = requireDirection(post.getDirectionId(), actor.organizationId());
        return response(post, memberNames(actor.organizationId()), Map.of(direction.getId(), direction.getTitle()));
    }

    private void requireContributor(CurrentActor actor) {
        if (actor.roles().stream().noneMatch(role -> role == RoleCode.ORG_ADMIN || role == RoleCode.TEAM_MANAGER || role == RoleCode.MEMBER)) {
            throw ApiException.forbidden("当前角色不能提交内容任务");
        }
    }

    private void validatePublishDate(LocalDate publishDate) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (publishDate.isBefore(campaign.startDate()) || publishDate.isAfter(campaign.endDate())) {
            throw ApiException.badRequest("OUTSIDE_CAMPAIGN_PERIOD", "发布日期不在内容任务周期内");
        }
        if (publishDate.isAfter(today)) {
            throw ApiException.badRequest("FUTURE_PUBLISH_DATE", "不能提交未来日期的发布内容");
        }
    }

    private long validCount(CurrentActor actor, ContentPlatform platform) {
        return posts.countByOrganizationIdAndMembershipIdAndPlatformAndReviewStatusAndPublishDateBetween(
                actor.organizationId(), actor.membershipId(), platform, ContentReviewStatus.VALID,
                campaign.startDate(), campaign.endDate());
    }

    private List<ContentDtos.MemberProgress> memberProgress(UUID organizationId, Set<UUID> membershipIds) {
        if (membershipIds != null && membershipIds.isEmpty()) return List.of();
        String memberScope = membershipIds == null ? "" : "and m.id in (:membershipIds)";
        var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("startDate", campaign.startDate())
                .addValue("endDate", campaign.endDate())
                .addValue("organizationId", organizationId)
                .addValue("membershipIds", membershipIds == null ? Set.of() : membershipIds);
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query("""
                select m.id membership_id, u.display_name,
                       count(cp.id) filter (where cp.platform='XIAOHONGSHU' and cp.review_status='VALID'
                           and cp.publish_date between :startDate and :endDate) xhs_count,
                       count(cp.id) filter (where cp.platform='DOUYIN' and cp.review_status='VALID'
                           and cp.publish_date between :startDate and :endDate) douyin_count
                from memberships m
                join user_accounts u on u.id=m.user_id
                left join content_posts cp on cp.membership_id=m.id and cp.organization_id=m.organization_id
                where m.organization_id=:organizationId and m.status='ACTIVE'
                  and exists (
                    select 1 from membership_roles mr join roles r on r.id=mr.role_id
                    where mr.membership_id=m.id and r.code in ('MEMBER','TEAM_MANAGER')
                  )
                """ + memberScope + """
                group by m.id, u.display_name
                order by u.display_name
                """, parameters, (row, index) -> new ContentDtos.MemberProgress(
                row.getObject("membership_id", UUID.class), row.getString("display_name"),
                row.getLong("xhs_count"), row.getLong("douyin_count")
        ));
    }

    private Set<UUID> managedMemberIds(CurrentActor actor) {
        if (actor.managedTeamIds().isEmpty()) return Set.of();
        Set<UUID> result = new HashSet<>();
        actor.managedTeamIds().forEach(teamId -> result.addAll(jdbc.queryForList("""
                select tm.membership_id from team_members tm join teams t on t.id=tm.team_id
                where tm.team_id=? and t.active=true
                """, UUID.class, teamId)));
        return result;
    }

    private Map<UUID, String> memberNames(UUID organizationId) {
        Map<UUID, String> result = new HashMap<>();
        jdbc.query("""
                select m.id, u.display_name from memberships m
                join user_accounts u on u.id=m.user_id where m.organization_id=?
                """, (row, index) -> Map.entry(
                row.getObject("id", UUID.class), row.getString("display_name")), organizationId)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private ContentDtos.CampaignResponse campaignResponse() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return new ContentDtos.CampaignResponse(campaign.name(), campaign.startDate(), campaign.endDate(),
                campaign.xiaohongshuTarget(), campaign.douyinTarget(), today,
                !today.isBefore(campaign.startDate()) && !today.isAfter(campaign.endDate()));
    }

    private ContentDirectionEntity requireDirection(UUID id, UUID organizationId) {
        return directions.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> ApiException.notFound("CONTENT_DIRECTION_NOT_FOUND", "内容方向不存在"));
    }

    private ContentPostEntity requirePost(UUID id, UUID organizationId) {
        return posts.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> ApiException.notFound("CONTENT_POST_NOT_FOUND", "内容记录不存在"));
    }

    private ContentDtos.DirectionResponse directionResponse(ContentDirectionEntity row) {
        return new ContentDtos.DirectionResponse(row.getId(), row.getTitle(), row.getDescription(), row.isActive(),
                row.getCreatedAt(), row.getUpdatedAt());
    }

    private ContentDtos.PostResponse response(ContentPostEntity row, Map<UUID, String> names, Map<UUID, String> directionMap) {
        return new ContentDtos.PostResponse(
                row.getId(), row.getMembershipId(), names.getOrDefault(row.getMembershipId(), "已停用成员"),
                row.getPlatform(), row.getPublishDate(), row.getDirectionId(),
                directionMap.getOrDefault(row.getDirectionId(), "已停用方向"), row.getTopic(), row.getContentUrl(),
                row.getViewCount(), row.getLikeCount(), row.getFavoriteCount(), row.isOriginalDeclared(),
                row.getReviewStatus(), row.getReviewReason(), row.getReviewedAt(), row.getCreatedAt(), row.getUpdatedAt(),
                weightScore(row), row.getVersion());
    }

    private long weightScore(ContentPostEntity row) {
        long review = switch (row.getReviewStatus()) {
            case VALID -> 1_000_000L;
            case SUBMITTED -> 100_000L;
            case INVALID -> 0L;
        };
        return review + Math.min(row.getViewCount(), 1_000_000L)
                + Math.min(row.getLikeCount(), 1_000_000L) * 10
                + Math.min(row.getFavoriteCount(), 1_000_000L) * 20;
    }

    private Sort circleSort(String value) {
        return switch (value == null ? "recommended" : value) {
            case "latest" -> Sort.by(Sort.Order.desc("publishDate"), Sort.Order.desc("createdAt"));
            case "views" -> Sort.by(Sort.Order.desc("viewCount"), Sort.Order.desc("publishDate"));
            case "likes" -> Sort.by(Sort.Order.desc("likeCount"), Sort.Order.desc("publishDate"));
            case "favorites" -> Sort.by(Sort.Order.desc("favoriteCount"), Sort.Order.desc("publishDate"));
            default -> Sort.by(Sort.Order.desc("weightScore"), Sort.Order.desc("publishDate"),
                    Sort.Order.desc("createdAt"));
        };
    }

    private String required(String value, String code, String message) {
        String normalized = TextNormalizer.optional(value);
        if (normalized == null) throw ApiException.badRequest(code, message);
        return normalized;
    }

    private String validUrl(String value) {
        String raw = required(value, "CONTENT_URL_REQUIRED", "内容链接不能为空");
        Matcher matcher = HTTP_URL_PATTERN.matcher(raw);
        String url = matcher.find() ? matcher.group() : raw;
        url = TRAILING_SHARE_PUNCTUATION.matcher(url).replaceFirst("");
        try {
            URI parsed = URI.create(url);
            if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme())) || parsed.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("INVALID_CONTENT_URL", "内容链接必须是有效的 HTTP 或 HTTPS 地址");
        }
    }
}
