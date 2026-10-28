package com.yingge.cloud.content.infrastructure;

import com.yingge.cloud.content.domain.ContentPlatform;
import com.yingge.cloud.content.domain.ContentReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "content_posts")
public class ContentPostEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentPlatform platform;
    @Column(name = "publish_date", nullable = false)
    private LocalDate publishDate;
    @Column(name = "direction_id", nullable = false)
    private UUID directionId;
    @Column(nullable = false)
    private String topic;
    @Column(name = "content_url", nullable = false)
    private String contentUrl;
    @Column(name = "view_count", nullable = false)
    private long viewCount;
    @Column(name = "like_count", nullable = false)
    private long likeCount;
    @Column(name = "favorite_count", nullable = false)
    private long favoriteCount;
    @Column(name = "original_declared", nullable = false)
    private boolean originalDeclared;
    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ContentReviewStatus reviewStatus;
    @Column(name = "review_reason")
    private String reviewReason;
    @Column(name = "reviewed_by_membership_id")
    private UUID reviewedByMembershipId;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Formula("((case review_status when 'VALID' then 1000000 when 'SUBMITTED' then 100000 else 0 end) + least(view_count, 1000000) + least(like_count, 1000000) * 10 + least(favorite_count, 1000000) * 20)")
    private long weightScore;
    @Version
    private long version;

    protected ContentPostEntity() {}

    public ContentPostEntity(UUID organizationId, UUID membershipId, ContentPlatform platform,
                             LocalDate publishDate, UUID directionId, String topic, String contentUrl,
                             long viewCount, long likeCount, long favoriteCount, boolean originalDeclared) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.membershipId = membershipId;
        this.platform = platform;
        this.publishDate = publishDate;
        this.directionId = directionId;
        this.topic = topic;
        this.contentUrl = contentUrl;
        this.viewCount = viewCount;
        this.likeCount = likeCount;
        this.favoriteCount = favoriteCount;
        this.originalDeclared = originalDeclared;
        this.reviewStatus = ContentReviewStatus.SUBMITTED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateMetrics(long viewCount, long likeCount, long favoriteCount) {
        this.viewCount = viewCount;
        this.likeCount = likeCount;
        this.favoriteCount = favoriteCount;
        this.updatedAt = Instant.now();
    }

    public void review(ContentReviewStatus status, String reason, UUID reviewerId) {
        this.reviewStatus = status;
        this.reviewReason = reason;
        this.reviewedByMembershipId = reviewerId;
        this.reviewedAt = Instant.now();
        this.updatedAt = this.reviewedAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getMembershipId() { return membershipId; }
    public ContentPlatform getPlatform() { return platform; }
    public LocalDate getPublishDate() { return publishDate; }
    public UUID getDirectionId() { return directionId; }
    public String getTopic() { return topic; }
    public String getContentUrl() { return contentUrl; }
    public long getViewCount() { return viewCount; }
    public long getLikeCount() { return likeCount; }
    public long getFavoriteCount() { return favoriteCount; }
    public boolean isOriginalDeclared() { return originalDeclared; }
    public ContentReviewStatus getReviewStatus() { return reviewStatus; }
    public String getReviewReason() { return reviewReason; }
    public Instant getReviewedAt() { return reviewedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getWeightScore() { return weightScore; }
    public long getVersion() { return version; }
}
