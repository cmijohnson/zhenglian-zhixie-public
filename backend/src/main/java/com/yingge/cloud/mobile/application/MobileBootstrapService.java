package com.yingge.cloud.mobile.application;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.mobile.api.MobileDtos;
import com.yingge.cloud.permission.domain.RoleCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
public class MobileBootstrapService {
    private final CurrentActorProvider actors;
    private final JdbcTemplate jdbc;

    public MobileBootstrapService(CurrentActorProvider actors, JdbcTemplate jdbc) {
        this.actors = actors;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public MobileDtos.Bootstrap bootstrap() {
        CloudPrincipal principal = actors.requirePrincipal();
        var actor = principal.actor();
        Long urgent = jdbc.queryForObject("""
                select count(*) from reminders
                where organization_id=? and assigned_membership_id=? and status='PENDING' and remind_at<=now()
                """, Long.class, actor.organizationId(), actor.membershipId());
        Long notifications = jdbc.queryForObject("""
                select count(*) from system_notifications
                where organization_id=? and recipient_membership_id=? and read_at is null and expires_at>now()
                """, Long.class, actor.organizationId(), actor.membershipId());
        Set<String> capabilities = new LinkedHashSet<>();
        capabilities.add("CUSTOMER_READ");
        capabilities.add("NOTIFICATION_READ");
        capabilities.add("TASKS_READ");
        capabilities.add("CONTENT_READ");
        capabilities.add("ANNOUNCEMENTS_READ");
        capabilities.add("RELEASES_READ");
        capabilities.add("AGENT_SELF_MANAGE");
        capabilities.add("DEVICE_SELF_MANAGE");
        if (actor.canWriteCustomers()) {
            capabilities.add("CUSTOMER_WRITE");
            capabilities.add("DEAL_REQUEST");
            capabilities.add("QQ_WEB_WORKBENCH");
            capabilities.add("DAILY_REPORT_WRITE");
            capabilities.add("TASKS_WRITE");
            capabilities.add("PERFORMANCE_WRITE");
            capabilities.add("CONSULTATION_WRITE");
            capabilities.add("CONTENT_WRITE");
            capabilities.add("POOL_CLAIM");
            capabilities.add("SECOND_LINE_CLAIM");
            capabilities.add("ASSISTANT_READ");
        }
        if (actor.isTeamManager() || actor.isAdmin()) {
            capabilities.add("TEAM_DATA");
            capabilities.add("LEADERSHIP_DASHBOARD_READ");
            capabilities.add("MARKET_INSIGHTS_READ");
            capabilities.add("BULK_TRANSFER");
        }
        if (actor.isAdmin()) {
            capabilities.add("ADMIN_DEAL_REVIEW");
            capabilities.add("ADMIN_MANAGEMENT");
            capabilities.add("ADMIN_OVERVIEW_READ");
            capabilities.add("ADMIN_MEMBERS_WRITE");
            capabilities.add("ADMIN_TEAMS_WRITE");
            capabilities.add("ADMIN_REGISTRATION_CODES_WRITE");
            capabilities.add("ADMIN_GOVERNANCE_WRITE");
            capabilities.add("ADMIN_PERFORMANCE_WRITE");
            capabilities.add("ADMIN_CONTENT_REVIEW");
            capabilities.add("ADMIN_ANNOUNCEMENTS_WRITE");
            capabilities.add("ADMIN_DICTIONARIES_WRITE");
            capabilities.add("ADMIN_ASSISTANT_SETTINGS_WRITE");
            capabilities.add("ADMIN_CUSTOMER_INTELLIGENCE_WRITE");
            capabilities.add("ADMIN_ENRICHMENT_WRITE");
            capabilities.add("ADMIN_AGENT_MANAGEMENT");
            capabilities.add("ADMIN_AUDIT_READ");
            capabilities.add("ADMIN_WEBHOOKS_WRITE");
            capabilities.add("ADMIN_LEADERSHIP_SETTINGS_WRITE");
            capabilities.add("ADMIN_FOLLOWUP_POLICY_WRITE");
            capabilities.add("ADMIN_CONSULTATION_SETTINGS_WRITE");
        }
        if (actor.hasRole(RoleCode.VIEWER)) capabilities.add("VIEW_ONLY");
        Set<String> modes = actor.isAdmin() ? Set.of("EMPLOYEE", "ADMIN") : Set.of("EMPLOYEE");
        return new MobileDtos.Bootstrap(MobileDtos.Actor.from(principal), Instant.now(), "Asia/Shanghai",
                modes, Set.copyOf(capabilities), new MobileDtos.Unread(urgent == null ? 0 : urgent,
                notifications == null ? 0 : notifications));
    }
}
