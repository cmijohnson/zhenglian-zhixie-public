package com.yingge.cloud.forum.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.forum.api.ForumDtos;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class ForumService {
    private static final String TAG_SEPARATOR = "\u001f";
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final AuditService audit;

    public ForumService(JdbcTemplate jdbc, CurrentActorProvider actors, AuditService audit) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ForumDtos.Board> listBoards(boolean includeInactive) {
        UUID organizationId = includeInactive ? moderator().organizationId() : publicOrganizationId();
        return jdbc.query("""
                SELECT b.id, b.name, b.slug, b.description, b.icon, b.color, b.sort_order, b.active,
                       count(p.id) FILTER (WHERE p.status = 'PUBLISHED') AS post_count
                FROM forum_boards b
                LEFT JOIN forum_posts p ON p.board_id = b.id
                WHERE b.organization_id = ? AND (? OR b.active)
                GROUP BY b.id
                ORDER BY b.sort_order, b.name
                """, (rs, ignored) -> new ForumDtos.Board(
                rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("slug"),
                rs.getString("description"), rs.getString("icon"), rs.getString("color"),
                rs.getInt("sort_order"), rs.getBoolean("active"), rs.getLong("post_count")
        ), organizationId, includeInactive);
    }

    @Transactional(readOnly = true)
    public ForumDtos.Page<ForumDtos.PostSummary> listPosts(
            String query, String board, String tag, String type, int requestedPage, int requestedSize) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(50, Math.max(1, requestedSize));
        UUID organizationId = publicOrganizationId();
        String normalizedQuery = text(query);
        String normalizedBoard = text(board);
        String normalizedTag = text(tag);
        String normalizedType = text(type);
        String filter = """
                p.organization_id = ? AND p.status = 'PUBLISHED'
                AND (CAST(? AS varchar) IS NULL OR b.slug = ?)
                AND (CAST(? AS varchar) IS NULL OR p.post_type = ?)
                AND (CAST(? AS varchar) IS NULL OR lower(p.title || ' ' || p.content) LIKE '%' || lower(?) || '%')
                AND (CAST(? AS varchar) IS NULL OR EXISTS (
                    SELECT 1 FROM forum_post_tags ft WHERE ft.post_id = p.id AND lower(ft.tag) = lower(?)
                ))
                """;
        Object[] args = {
                organizationId, normalizedBoard, normalizedBoard, normalizedType, normalizedType,
                normalizedQuery, normalizedQuery, normalizedTag, normalizedTag
        };
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM forum_posts p
                JOIN forum_boards b ON b.id = p.board_id
                WHERE """ + " " + filter, Long.class, args);
        Object[] pageArgs = Arrays.copyOf(args, args.length + 2);
        pageArgs[args.length] = size;
        pageArgs[args.length + 1] = page * size;
        List<ForumDtos.PostSummary> items = jdbc.query("""
                SELECT p.id, p.board_id, b.name board_name, b.slug board_slug, p.author_display_name,
                       p.post_type, p.title, p.excerpt, p.pinned, p.locked, p.view_count, p.published_at,
                       (SELECT count(*) FROM forum_likes l WHERE l.post_id = p.id) like_count,
                       (SELECT count(*) FROM forum_comments c
                         WHERE c.post_id = p.id AND c.status = 'PUBLISHED') comment_count,
                       (SELECT coalesce(string_agg(t.tag, ? ORDER BY t.tag), '')
                          FROM forum_post_tags t WHERE t.post_id = p.id) tags
                FROM forum_posts p
                JOIN forum_boards b ON b.id = p.board_id
                WHERE """ + " " + filter + """
                ORDER BY p.pinned DESC, p.published_at DESC
                LIMIT ? OFFSET ?
                """, (rs, ignored) -> summary(
                rs.getObject("id", UUID.class), rs.getObject("board_id", UUID.class),
                rs.getString("board_name"), rs.getString("board_slug"), rs.getString("author_display_name"),
                rs.getString("post_type"), rs.getString("title"), rs.getString("excerpt"),
                splitTags(rs.getString("tags")), rs.getBoolean("pinned"), rs.getBoolean("locked"),
                rs.getLong("view_count"), rs.getLong("like_count"), rs.getLong("comment_count"),
                rs.getTimestamp("published_at").toInstant()
        ), prepend(TAG_SEPARATOR, pageArgs));
        long count = total == null ? 0 : total;
        return new ForumDtos.Page<>(items, page, size, count, (int) Math.ceil(count / (double) size));
    }

    @Transactional
    public ForumDtos.PostDetail getPost(UUID id, boolean countView) {
        UUID organizationId = publicOrganizationId();
        if (countView) {
            jdbc.update("""
                    UPDATE forum_posts SET view_count = view_count + 1
                    WHERE id = ? AND organization_id = ? AND status = 'PUBLISHED'
                    """, id, organizationId);
        }
        return postDetail(id, organizationId, false);
    }

    @Transactional
    public ForumDtos.PostDetail createPost(ForumDtos.CreatePost body) {
        CurrentActor actor = writer();
        boolean announcement = "ANNOUNCEMENT".equalsIgnoreCase(body.postType());
        if (announcement && !actor.isAdmin()) {
            throw ApiException.forbidden("只有组织管理员可以发布公告");
        }
        requireBoard(body.boardId(), actor.organizationId(), true);
        UUID id = UUID.randomUUID();
        String content = required(body.content(), "POST_CONTENT_REQUIRED", "帖子内容不能为空");
        jdbc.update("""
                INSERT INTO forum_posts (
                    id, organization_id, board_id, author_membership_id, author_display_name,
                    post_type, title, content, excerpt, pinned
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actor.organizationId(), body.boardId(), actor.membershipId(), actor.displayName(),
                announcement ? "ANNOUNCEMENT" : "POST",
                required(body.title(), "POST_TITLE_REQUIRED", "帖子标题不能为空"), content,
                excerpt(content), announcement);
        replaceTags(id, body.tags());
        audit.record(actor, announcement ? "FORUM_ANNOUNCEMENT_PUBLISHED" : "FORUM_POST_CREATED",
                "FORUM_POST", id.toString(), "SUCCESS", null);
        return postDetail(id, actor.organizationId(), true);
    }

    @Transactional
    public ForumDtos.PostDetail updatePost(UUID id, ForumDtos.UpdatePost body) {
        CurrentActor actor = writer();
        PostOwner owner = requireOwner(id, actor.organizationId());
        if (!actor.isAdmin() && !Objects.equals(owner.membershipId(), actor.membershipId())) {
            throw ApiException.forbidden("只能编辑自己发布的帖子");
        }
        requireBoard(body.boardId(), actor.organizationId(), true);
        String content = required(body.content(), "POST_CONTENT_REQUIRED", "帖子内容不能为空");
        jdbc.update("""
                UPDATE forum_posts
                SET board_id = ?, title = ?, content = ?, excerpt = ?, updated_at = now(), version = version + 1
                WHERE id = ? AND organization_id = ? AND status <> 'DELETED'
                """, body.boardId(), required(body.title(), "POST_TITLE_REQUIRED", "帖子标题不能为空"),
                content, excerpt(content), id, actor.organizationId());
        replaceTags(id, body.tags());
        audit.record(actor, "FORUM_POST_UPDATED", "FORUM_POST", id.toString(), "SUCCESS", null);
        return postDetail(id, actor.organizationId(), true);
    }

    @Transactional
    public ForumDtos.Attachment addAttachment(UUID postId, MultipartFile file) {
        CurrentActor actor = writer();
        PostOwner owner = requireOwner(postId, actor.organizationId());
        if (!actor.isAdmin() && !Objects.equals(owner.membershipId(), actor.membershipId())) {
            throw ApiException.forbidden("只能向自己的帖子上传附件");
        }
        if (file.isEmpty()) throw ApiException.badRequest("EMPTY_FILE", "附件不能为空");
        if (file.getSize() > 25L * 1024 * 1024) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "单个附件不能超过 25MB");
        }
        UUID id = UUID.randomUUID();
        String fileName = file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename();
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        try {
            jdbc.update("""
                    INSERT INTO forum_attachments
                        (id, post_id, file_name, content_type, file_size, content)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, id, postId, fileName, contentType, file.getSize(), file.getBytes());
        } catch (IOException exception) {
            throw ApiException.badRequest("FILE_READ_FAILED", "无法读取上传附件");
        }
        return new ForumDtos.Attachment(id, fileName, contentType, file.getSize(),
                "/api/v1/forum/public/attachments/" + id);
    }

    @Transactional
    public ForumDtos.Comment addComment(UUID postId, ForumDtos.CreateComment body) {
        CurrentActor actor = writer();
        Boolean locked = jdbc.query("""
                SELECT locked FROM forum_posts
                WHERE id = ? AND organization_id = ? AND status = 'PUBLISHED'
                """, rs -> rs.next() ? rs.getBoolean(1) : null, postId, actor.organizationId());
        if (locked == null) throw ApiException.notFound("FORUM_POST_NOT_FOUND", "帖子不存在");
        if (locked) throw ApiException.conflict("FORUM_POST_LOCKED", "帖子已锁定，不能继续评论");
        if (body.parentId() != null) {
            Integer parent = jdbc.queryForObject("""
                    SELECT count(*) FROM forum_comments
                    WHERE id = ? AND post_id = ? AND status = 'PUBLISHED'
                    """, Integer.class, body.parentId(), postId);
            if (parent == null || parent == 0) {
                throw ApiException.badRequest("PARENT_COMMENT_NOT_FOUND", "回复的评论不存在");
            }
        }
        UUID id = UUID.randomUUID();
        String content = required(body.content(), "COMMENT_REQUIRED", "评论内容不能为空");
        jdbc.update("""
                INSERT INTO forum_comments (
                    id, post_id, author_membership_id, author_display_name, parent_id, content
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, id, postId, actor.membershipId(), actor.displayName(), body.parentId(), content);
        return new ForumDtos.Comment(id, body.parentId(), actor.displayName(), content, Instant.now());
    }

    @Transactional
    public ForumDtos.LikeResult toggleLike(UUID postId) {
        CurrentActor actor = writer();
        requirePublishedPost(postId, actor.organizationId());
        int removed = jdbc.update("DELETE FROM forum_likes WHERE post_id = ? AND membership_id = ?",
                postId, actor.membershipId());
        boolean liked = removed == 0;
        if (liked) {
            jdbc.update("INSERT INTO forum_likes (post_id, membership_id) VALUES (?, ?)",
                    postId, actor.membershipId());
        }
        Long count = jdbc.queryForObject("SELECT count(*) FROM forum_likes WHERE post_id = ?",
                Long.class, postId);
        return new ForumDtos.LikeResult(liked, count == null ? 0 : count);
    }

    @Transactional
    public void report(UUID postId, ForumDtos.ReportRequest body) {
        CurrentActor actor = writer();
        requirePublishedPost(postId, actor.organizationId());
        try {
            jdbc.update("""
                    INSERT INTO forum_reports (
                        id, organization_id, post_id, reporter_membership_id, reason
                    ) VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.organizationId(), postId, actor.membershipId(),
                    required(body.reason(), "REPORT_REASON_REQUIRED", "请填写举报原因"));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("REPORT_EXISTS", "你已经举报过这篇帖子");
        }
    }

    @Transactional
    public void markAnnouncementRead(UUID postId) {
        CurrentActor actor = actors.require();
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM forum_posts
                WHERE id = ? AND organization_id = ? AND post_type = 'ANNOUNCEMENT' AND status = 'PUBLISHED'
                """, Integer.class, postId, actor.organizationId());
        if (found == null || found == 0) {
            throw ApiException.notFound("ANNOUNCEMENT_NOT_FOUND", "公告不存在");
        }
        jdbc.update("""
                INSERT INTO forum_announcement_reads (post_id, membership_id)
                VALUES (?, ?) ON CONFLICT (post_id, membership_id)
                DO UPDATE SET read_at = now()
                """, postId, actor.membershipId());
    }

    @Transactional
    public ForumDtos.Board createBoard(ForumDtos.BoardRequest body) {
        CurrentActor actor = admin();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO forum_boards (
                    id, organization_id, name, slug, description, icon, color, sort_order, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, actor.organizationId(), required(body.name(), "BOARD_NAME_REQUIRED", "板块名称不能为空"),
                slug(body.slug()), text(body.description()), defaultText(body.icon(), "💬"),
                defaultText(body.color(), "#3157d5"), body.sortOrder() == null ? 100 : body.sortOrder(),
                body.active() == null || body.active());
        audit.record(actor, "FORUM_BOARD_CREATED", "FORUM_BOARD", id.toString(), "SUCCESS", null);
        return board(id, actor.organizationId());
    }

    @Transactional
    public ForumDtos.Board updateBoard(UUID id, ForumDtos.BoardRequest body) {
        CurrentActor actor = admin();
        int changed = jdbc.update("""
                UPDATE forum_boards
                SET name = ?, slug = ?, description = ?, icon = ?, color = ?, sort_order = ?,
                    active = ?, updated_at = now()
                WHERE id = ? AND organization_id = ?
                """, required(body.name(), "BOARD_NAME_REQUIRED", "板块名称不能为空"), slug(body.slug()),
                text(body.description()), defaultText(body.icon(), "💬"),
                defaultText(body.color(), "#3157d5"), body.sortOrder() == null ? 100 : body.sortOrder(),
                body.active() == null || body.active(), id, actor.organizationId());
        if (changed == 0) throw ApiException.notFound("FORUM_BOARD_NOT_FOUND", "板块不存在");
        audit.record(actor, "FORUM_BOARD_UPDATED", "FORUM_BOARD", id.toString(), "SUCCESS", null);
        return board(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<ForumDtos.Report> listReports() {
        CurrentActor actor = moderator();
        return jdbc.query("""
                SELECT r.id, r.post_id, p.title, u.display_name reporter_name, r.reason, r.status, r.created_at
                FROM forum_reports r
                JOIN forum_posts p ON p.id = r.post_id
                JOIN memberships m ON m.id = r.reporter_membership_id
                JOIN user_accounts u ON u.id = m.user_id
                WHERE r.organization_id = ?
                ORDER BY CASE WHEN r.status = 'OPEN' THEN 0 ELSE 1 END, r.created_at DESC
                LIMIT 300
                """, (rs, ignored) -> new ForumDtos.Report(
                rs.getObject("id", UUID.class), rs.getObject("post_id", UUID.class),
                rs.getString("title"), rs.getString("reporter_name"), rs.getString("reason"),
                rs.getString("status"), rs.getTimestamp("created_at").toInstant()
        ), actor.organizationId());
    }

    @Transactional
    public ForumDtos.PostDetail moderate(UUID postId, ForumDtos.ModerationRequest body) {
        CurrentActor actor = moderator();
        String action = body.action().trim().toUpperCase(Locale.ROOT);
        String assignment = switch (action) {
            case "HIDE" -> "status = 'HIDDEN'";
            case "RESTORE" -> "status = 'PUBLISHED'";
            case "PIN" -> "pinned = true";
            case "UNPIN" -> "pinned = false";
            case "LOCK" -> "locked = true";
            case "UNLOCK" -> "locked = false";
            case "DELETE" -> "status = 'DELETED'";
            default -> throw ApiException.badRequest("INVALID_MODERATION_ACTION", "不支持的管理操作");
        };
        int changed = jdbc.update("UPDATE forum_posts SET " + assignment +
                        ", updated_at = now(), version = version + 1 WHERE id = ? AND organization_id = ?",
                postId, actor.organizationId());
        if (changed == 0) throw ApiException.notFound("FORUM_POST_NOT_FOUND", "帖子不存在");
        jdbc.update("""
                INSERT INTO forum_moderation_actions
                    (id, organization_id, post_id, actor_membership_id, action, reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.organizationId(), postId, actor.membershipId(), action,
                text(body.reason()));
        if (action.equals("HIDE") || action.equals("DELETE")) {
            jdbc.update("""
                    UPDATE forum_reports SET status = 'RESOLVED', resolved_by_membership_id = ?,
                        resolution_note = ?, resolved_at = now()
                    WHERE post_id = ? AND status = 'OPEN'
                    """, actor.membershipId(), defaultText(body.reason(), "内容已处理"), postId);
        }
        audit.record(actor, "FORUM_POST_" + action, "FORUM_POST", postId.toString(), "SUCCESS",
                java.util.Map.of("reason", defaultText(body.reason(), "")));
        return postDetail(postId, actor.organizationId(), true);
    }

    @Transactional(readOnly = true)
    public ForumDtos.Overview overview() {
        CurrentActor actor = moderator();
        UUID org = actor.organizationId();
        long posts = count("SELECT count(*) FROM forum_posts WHERE organization_id = ? AND status = 'PUBLISHED'", org);
        long comments = count("""
                SELECT count(*) FROM forum_comments c JOIN forum_posts p ON p.id = c.post_id
                WHERE p.organization_id = ? AND c.status = 'PUBLISHED'
                """, org);
        long likes = count("""
                SELECT count(*) FROM forum_likes l JOIN forum_posts p ON p.id = l.post_id
                WHERE p.organization_id = ?
                """, org);
        long reports = count("SELECT count(*) FROM forum_reports WHERE organization_id = ? AND status = 'OPEN'", org);
        long members = count("SELECT count(*) FROM memberships WHERE organization_id = ? AND status = 'ACTIVE'", org);
        List<ForumDtos.AnnouncementRead> reads = jdbc.query("""
                SELECT p.id, p.title,
                       (SELECT count(*) FROM forum_announcement_reads r WHERE r.post_id = p.id) read_count
                FROM forum_posts p
                WHERE p.organization_id = ? AND p.post_type = 'ANNOUNCEMENT'
                  AND p.status = 'PUBLISHED'
                ORDER BY p.published_at DESC
                LIMIT 20
                """, (rs, ignored) -> new ForumDtos.AnnouncementRead(
                rs.getObject("id", UUID.class), rs.getString("title"),
                rs.getLong("read_count"), members
        ), org);
        return new ForumDtos.Overview(posts, comments, likes, reports, members,
                listPosts(null, null, null, null, 0, 5).items(), reads);
    }

    public FilePayload getAttachment(UUID id) {
        return jdbc.query("""
                SELECT a.file_name, a.content_type, a.content, a.external_url
                FROM forum_attachments a
                JOIN forum_posts p ON p.id = a.post_id
                WHERE a.id = ? AND p.status = 'PUBLISHED'
                """, rs -> {
            if (!rs.next()) throw ApiException.notFound("FORUM_ATTACHMENT_NOT_FOUND", "附件不存在");
            String external = rs.getString("external_url");
            if (external != null) {
                throw ApiException.notFound("EXTERNAL_ATTACHMENT", "外部附件请使用原始链接");
            }
            return new FilePayload(rs.getString("file_name"), rs.getString("content_type"),
                    rs.getBytes("content"));
        }, id);
    }

    private ForumDtos.PostDetail postDetail(UUID id, UUID organizationId, boolean includeHidden) {
        String status = includeHidden ? "p.status <> 'DELETED'" : "p.status = 'PUBLISHED'";
        ForumDtos.PostDetail detail = jdbc.query("""
                SELECT p.id, p.board_id, b.name board_name, b.slug board_slug, p.author_display_name,
                       p.post_type, p.title, p.content, p.pinned, p.locked, p.view_count,
                       p.published_at, p.updated_at,
                       (SELECT count(*) FROM forum_likes l WHERE l.post_id = p.id) like_count,
                       (SELECT count(*) FROM forum_comments c
                         WHERE c.post_id = p.id AND c.status = 'PUBLISHED') comment_count,
                       (SELECT coalesce(string_agg(t.tag, ? ORDER BY t.tag), '')
                          FROM forum_post_tags t WHERE t.post_id = p.id) tags
                FROM forum_posts p JOIN forum_boards b ON b.id = p.board_id
                WHERE p.id = ? AND p.organization_id = ? AND """ + " " + status,
                rs -> {
                    if (!rs.next()) {
                        throw ApiException.notFound("FORUM_POST_NOT_FOUND", "帖子不存在或已被隐藏");
                    }
                    return new ForumDtos.PostDetail(
                            rs.getObject("id", UUID.class), rs.getObject("board_id", UUID.class),
                            rs.getString("board_name"), rs.getString("board_slug"),
                            rs.getString("author_display_name"), rs.getString("post_type"),
                            rs.getString("title"), rs.getString("content"), splitTags(rs.getString("tags")),
                            rs.getBoolean("pinned"), rs.getBoolean("locked"), rs.getLong("view_count"),
                            rs.getLong("like_count"), rs.getLong("comment_count"),
                            rs.getTimestamp("published_at").toInstant(),
                            rs.getTimestamp("updated_at").toInstant(), List.of(), List.of());
                }, TAG_SEPARATOR, id, organizationId);
        List<ForumDtos.Attachment> attachments = jdbc.query("""
                SELECT id, file_name, content_type, file_size, external_url
                FROM forum_attachments WHERE post_id = ? ORDER BY created_at
                """, (rs, ignored) -> {
            UUID attachmentId = rs.getObject("id", UUID.class);
            String external = rs.getString("external_url");
            return new ForumDtos.Attachment(attachmentId, rs.getString("file_name"),
                    rs.getString("content_type"), rs.getLong("file_size"),
                    external == null ? "/api/v1/forum/public/attachments/" + attachmentId : external);
        }, id);
        List<ForumDtos.Comment> comments = jdbc.query("""
                SELECT id, parent_id, author_display_name, content, created_at
                FROM forum_comments
                WHERE post_id = ? AND status = 'PUBLISHED'
                ORDER BY created_at
                """, (rs, ignored) -> new ForumDtos.Comment(
                rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
                rs.getString("author_display_name"), rs.getString("content"),
                rs.getTimestamp("created_at").toInstant()
        ), id);
        return new ForumDtos.PostDetail(
                detail.id(), detail.boardId(), detail.boardName(), detail.boardSlug(),
                detail.authorDisplayName(), detail.postType(), detail.title(), detail.content(),
                detail.tags(), detail.pinned(), detail.locked(), detail.viewCount(), detail.likeCount(),
                detail.commentCount(), detail.publishedAt(), detail.updatedAt(), attachments, comments);
    }

    private ForumDtos.Board board(UUID id, UUID organizationId) {
        return jdbc.query("""
                SELECT b.id, b.name, b.slug, b.description, b.icon, b.color, b.sort_order, b.active,
                       count(p.id) FILTER (WHERE p.status = 'PUBLISHED') post_count
                FROM forum_boards b LEFT JOIN forum_posts p ON p.board_id = b.id
                WHERE b.id = ? AND b.organization_id = ?
                GROUP BY b.id
                """, rs -> {
            if (!rs.next()) throw ApiException.notFound("FORUM_BOARD_NOT_FOUND", "板块不存在");
            return new ForumDtos.Board(rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getString("slug"), rs.getString("description"), rs.getString("icon"),
                    rs.getString("color"), rs.getInt("sort_order"), rs.getBoolean("active"),
                    rs.getLong("post_count"));
        }, id, organizationId);
    }

    private void replaceTags(UUID postId, List<String> rawTags) {
        jdbc.update("DELETE FROM forum_post_tags WHERE post_id = ?", postId);
        if (rawTags == null) return;
        var tags = new LinkedHashSet<String>();
        rawTags.stream().map(this::text).filter(Objects::nonNull).limit(8).forEach(tags::add);
        tags.forEach(tag -> jdbc.update("INSERT INTO forum_post_tags (post_id, tag) VALUES (?, ?)", postId, tag));
    }

    private void requireBoard(UUID id, UUID organizationId, boolean active) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM forum_boards
                WHERE id = ? AND organization_id = ? AND (? = false OR active)
                """, Integer.class, id, organizationId, active);
        if (found == null || found == 0) throw ApiException.notFound("FORUM_BOARD_NOT_FOUND", "板块不存在");
    }

    private void requirePublishedPost(UUID id, UUID organizationId) {
        Integer found = jdbc.queryForObject("""
                SELECT count(*) FROM forum_posts
                WHERE id = ? AND organization_id = ? AND status = 'PUBLISHED'
                """, Integer.class, id, organizationId);
        if (found == null || found == 0) throw ApiException.notFound("FORUM_POST_NOT_FOUND", "帖子不存在");
    }

    private PostOwner requireOwner(UUID id, UUID organizationId) {
        return jdbc.query("""
                SELECT author_membership_id, post_type FROM forum_posts
                WHERE id = ? AND organization_id = ? AND status <> 'DELETED'
                """, rs -> {
            if (!rs.next()) throw ApiException.notFound("FORUM_POST_NOT_FOUND", "帖子不存在");
            return new PostOwner(rs.getObject("author_membership_id", UUID.class), rs.getString("post_type"));
        }, id, organizationId);
    }

    private CurrentActor writer() {
        CurrentActor actor = actors.require();
        if (!actor.canPostToForum()) throw ApiException.forbidden("当前账号没有论坛写入权限");
        return actor;
    }

    private CurrentActor moderator() {
        CurrentActor actor = actors.require();
        if (!actor.canModerateForum()) throw ApiException.forbidden("当前账号没有论坛管理权限");
        return actor;
    }

    private CurrentActor admin() {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin()) throw ApiException.forbidden("只有组织管理员可以管理论坛板块");
        return actor;
    }

    private UUID publicOrganizationId() {
        List<UUID> ids = jdbc.query("""
                SELECT id FROM organizations WHERE status = 'ACTIVE' ORDER BY created_at LIMIT 1
                """, (rs, ignored) -> rs.getObject("id", UUID.class));
        if (ids.isEmpty()) throw ApiException.unavailable("FORUM_NOT_READY", "论坛尚未初始化");
        return ids.getFirst();
    }

    private long count(String sql, UUID organizationId) {
        Long value = jdbc.queryForObject(sql, Long.class, organizationId);
        return value == null ? 0 : value;
    }

    private ForumDtos.PostSummary summary(
            UUID id, UUID boardId, String boardName, String boardSlug, String author,
            String type, String title, String excerpt, List<String> tags, boolean pinned,
            boolean locked, long views, long likes, long comments, Instant publishedAt) {
        return new ForumDtos.PostSummary(id, boardId, boardName, boardSlug, author, type, title,
                excerpt, tags, pinned, locked, views, likes, comments, publishedAt);
    }

    private List<String> splitTags(String value) {
        return value == null || value.isBlank() ? List.of() : List.of(value.split(TAG_SEPARATOR));
    }

    private Object[] prepend(Object value, Object[] values) {
        Object[] result = new Object[values.length + 1];
        result[0] = value;
        System.arraycopy(values, 0, result, 1, values.length);
        return result;
    }

    private String required(String value, String code, String message) {
        String normalized = text(value);
        if (normalized == null) throw ApiException.badRequest(code, message);
        return normalized;
    }

    private String text(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String defaultText(String value, String fallback) {
        String normalized = text(value);
        return normalized == null ? fallback : normalized;
    }

    private String slug(String value) {
        String normalized = required(value, "BOARD_SLUG_REQUIRED", "板块标识不能为空")
                .toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9-]{0,78}[a-z0-9]") && !normalized.matches("[a-z0-9]")) {
            throw ApiException.badRequest("INVALID_BOARD_SLUG", "板块标识只能包含小写字母、数字和连字符");
        }
        return normalized;
    }

    private String excerpt(String content) {
        String plain = content.replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\[(.*?)]\\(.*?\\)", "$1")
                .replaceAll("[*_`>~-]", "")
                .replaceAll("\\s+", " ").trim();
        return plain.length() <= 220 ? plain : plain.substring(0, 217) + "…";
    }

    private record PostOwner(UUID membershipId, String postType) {}

    public record FilePayload(String fileName, String contentType, byte[] content) {}
}
