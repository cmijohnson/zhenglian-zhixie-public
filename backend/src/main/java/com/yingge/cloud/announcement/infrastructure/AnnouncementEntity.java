package com.yingge.cloud.announcement.infrastructure;

import com.yingge.cloud.announcement.domain.AnnouncementPriority;
import com.yingge.cloud.announcement.domain.AnnouncementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Formula;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "announcements")
public class AnnouncementEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "author_membership_id", nullable = false)
    private UUID authorMembershipId;
    @Formula("(select account.display_name from memberships member join user_accounts account on account.id=member.user_id where member.id=author_membership_id)")
    private String authorDisplayName;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String content;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnnouncementPriority priority;
    @Column(nullable = false)
    private boolean pinned;
    @Column(name = "show_on_leadership_screen", nullable = false)
    private boolean showOnLeadershipScreen;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnnouncementStatus status;
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;
    @Column(name = "archived_at")
    private Instant archivedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected AnnouncementEntity() {}

    public AnnouncementEntity(UUID organizationId, UUID authorMembershipId, String title, String content,
                              AnnouncementPriority priority, boolean pinned, boolean showOnLeadershipScreen) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.authorMembershipId = authorMembershipId;
        this.title = title;
        this.content = content;
        this.priority = priority;
        this.pinned = pinned;
        this.showOnLeadershipScreen = showOnLeadershipScreen;
        this.status = AnnouncementStatus.PUBLISHED;
        this.publishedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String title, String content, AnnouncementPriority priority, boolean pinned,
                       boolean showOnLeadershipScreen) {
        this.title = title;
        this.content = content;
        this.priority = priority;
        this.pinned = pinned;
        this.showOnLeadershipScreen = showOnLeadershipScreen;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.status = AnnouncementStatus.ARCHIVED;
        this.pinned = false;
        this.showOnLeadershipScreen = false;
        this.archivedAt = Instant.now();
        this.updatedAt = this.archivedAt;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getAuthorMembershipId() { return authorMembershipId; }
    public String getAuthorDisplayName() { return authorDisplayName; }
    public String getTitle() { return title; }
    public String getContent() { return content; }
    public AnnouncementPriority getPriority() { return priority; }
    public boolean isPinned() { return pinned; }
    public boolean isShowOnLeadershipScreen() { return showOnLeadershipScreen; }
    public AnnouncementStatus getStatus() { return status; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getArchivedAt() { return archivedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
