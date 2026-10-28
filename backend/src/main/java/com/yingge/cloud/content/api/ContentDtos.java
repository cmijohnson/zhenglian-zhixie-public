package com.yingge.cloud.content.api;

import com.yingge.cloud.content.domain.ContentPlatform;
import com.yingge.cloud.content.domain.ContentReviewStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ContentDtos {
    private ContentDtos() {}

    public record DirectionRequest(
            @NotBlank @Size(max = 160) String title,
            @Size(max = 4000) String description,
            Boolean active
    ) {}

    public record DirectionResponse(UUID id, String title, String description, boolean active,
                                    Instant createdAt, Instant updatedAt) {}

    public record PostRequest(
            @NotNull ContentPlatform platform,
            @NotNull LocalDate publishDate,
            @NotNull UUID directionId,
            @NotBlank @Size(max = 240) String topic,
            @NotBlank @Size(max = 1000) String contentUrl,
            @PositiveOrZero long viewCount,
            @PositiveOrZero long likeCount,
            @PositiveOrZero long favoriteCount,
            boolean originalDeclared
    ) {}

    public record MetricsRequest(
            @PositiveOrZero long viewCount,
            @PositiveOrZero long likeCount,
            @PositiveOrZero long favoriteCount
    ) {}

    public record ReviewRequest(@NotNull ContentReviewStatus status, @Size(max = 1000) String reason) {}

    public record PostResponse(
            UUID id, UUID membershipId, String memberDisplayName, ContentPlatform platform,
            LocalDate publishDate, UUID directionId, String directionTitle, String topic, String contentUrl,
            long viewCount, long likeCount, long favoriteCount, boolean originalDeclared,
            ContentReviewStatus reviewStatus, String reviewReason, Instant reviewedAt,
            Instant createdAt, Instant updatedAt, long weightScore, long version
    ) {}

    public record SenderOption(UUID membershipId, String displayName) {}

    public record CampaignResponse(String name, LocalDate startDate, LocalDate endDate,
                                   int xiaohongshuTarget, int douyinTarget, LocalDate today, boolean active) {}
    public record ProgressResponse(long xiaohongshuValid, long douyinValid) {}
    public record MemberProgress(UUID membershipId, String displayName, long xiaohongshuValid, long douyinValid) {}
    public record OverviewResponse(
            CampaignResponse campaign,
            List<DirectionResponse> directions,
            ProgressResponse myProgress,
            List<PostResponse> myPosts,
            List<PostResponse> reviewQueue,
            List<MemberProgress> memberProgress,
            boolean administrator,
            boolean reviewer
    ) {}
}
