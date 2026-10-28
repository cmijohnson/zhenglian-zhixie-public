package com.yingge.cloud.permission.domain;

import java.util.Set;
import java.util.UUID;

public record CurrentActor(
        UUID userId,
        UUID membershipId,
        UUID organizationId,
        String username,
        String displayName,
        Set<RoleCode> roles,
        Set<UUID> teamIds,
        Set<UUID> managedTeamIds
) {
    public boolean hasRole(RoleCode role) {
        return roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(RoleCode.ORG_ADMIN);
    }

    public boolean isTeamManager() {
        return hasRole(RoleCode.TEAM_MANAGER);
    }

    public boolean canWriteCustomers() {
        return isAdmin() || isTeamManager() || hasRole(RoleCode.MEMBER);
    }

    public boolean canBulkTransfer() {
        return isAdmin() || isTeamManager();
    }

    public boolean canReviewTeamOperations() {
        return isAdmin() || isTeamManager();
    }

    public boolean canPublishAnnouncements() {
        return isAdmin() || isTeamManager();
    }

    public boolean canPostToForum() {
        return isAdmin() || isTeamManager() || hasRole(RoleCode.MEMBER);
    }

    public boolean canModerateForum() {
        return isAdmin() || isTeamManager();
    }
}
