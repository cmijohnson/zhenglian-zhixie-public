package com.yingge.cloud.forum.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ForumDtos {
    private ForumDtos() {}

    public record Board(
            UUID id, String name, String slug, String description, String icon, String color,
            int sortOrder, boolean active, long postCount
    ) {}

    public record Attachment(
            UUID id, String fileName, String contentType, long fileSize, String url
    ) {}

    public record Comment(
            UUID id, UUID parentId, String authorDisplayName, String content, Instant createdAt
    ) {}

    public record PostSummary(
            UUID id, UUID boardId, String boardName, String boardSlug, String authorDisplayName,
            String postType, String title, String excerpt, List<String> tags, boolean pinned,
            boolean locked, long viewCount, long likeCount, long commentCount, Instant publishedAt
    ) {}

    public record PostDetail(
            UUID id, UUID boardId, String boardName, String boardSlug, String authorDisplayName,
            String postType, String title, String content, List<String> tags, boolean pinned,
            boolean locked, long viewCount, long likeCount, long commentCount, Instant publishedAt,
            Instant updatedAt, List<Attachment> attachments, List<Comment> comments
    ) {}

    public record Page<T>(List<T> items, int page, int size, long total, int pages) {}

    public record CreatePost(
            @NotNull UUID boardId,
            @NotBlank @Size(max = 180) String title,
            @NotBlank @Size(max = 50000) String content,
            @Size(max = 8) List<@Size(max = 48) String> tags,
            String postType
    ) {}

    public record UpdatePost(
            @NotNull UUID boardId,
            @NotBlank @Size(max = 180) String title,
            @NotBlank @Size(max = 50000) String content,
            @Size(max = 8) List<@Size(max = 48) String> tags
    ) {}

    public record CreateComment(
            UUID parentId,
            @NotBlank @Size(max = 4000) String content
    ) {}

    public record ReportRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ModerationRequest(
            @NotBlank String action,
            @Size(max = 500) String reason
    ) {}

    public record BoardRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 80) String slug,
            @Size(max = 240) String description,
            @Size(max = 24) String icon,
            @Size(max = 16) String color,
            Integer sortOrder,
            Boolean active
    ) {}

    public record LikeResult(boolean liked, long likeCount) {}

    public record Report(
            UUID id, UUID postId, String postTitle, String reporterDisplayName, String reason,
            String status, Instant createdAt
    ) {}

    public record Overview(
            long publishedPosts, long comments, long likes, long openReports, long members,
            List<PostSummary> popularPosts, List<AnnouncementRead> announcementReads
    ) {}

    public record AnnouncementRead(UUID postId, String title, long readCount, long totalMembers) {}
}
