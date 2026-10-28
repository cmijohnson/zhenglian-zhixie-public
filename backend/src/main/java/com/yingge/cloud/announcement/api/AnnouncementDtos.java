package com.yingge.cloud.announcement.api;

import com.yingge.cloud.announcement.domain.AnnouncementPriority;
import com.yingge.cloud.announcement.domain.AnnouncementStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class AnnouncementDtos {
    private AnnouncementDtos() {}

    public record UpsertRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 10000) String content,
            @NotNull AnnouncementPriority priority,
            boolean pinned,
            boolean showOnLeadershipScreen
    ) {}

    public record Response(
            UUID id, UUID authorMembershipId, String authorDisplayName, String title, String content,
            AnnouncementPriority priority, boolean pinned, boolean showOnLeadershipScreen,
            AnnouncementStatus status,
            Instant publishedAt, Instant archivedAt, Instant updatedAt, long version
    ) {}
}
