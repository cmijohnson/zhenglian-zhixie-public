package com.yingge.cloud.mobile.application;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.mobile.api.MobileDtos;
import com.yingge.cloud.permission.application.PermissionService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class MobileAdminOverviewService {
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;

    public MobileAdminOverviewService(JdbcTemplate jdbc, CurrentActorProvider actors,
                                      PermissionService permissions) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public MobileDtos.AdminOverview overview() {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        return jdbc.queryForObject("""
                select
                  (select count(*) from customer_deal_requests
                     where organization_id=? and status='PENDING') pending_deals,
                  (select count(*) from customer_classification_review_queue
                     where organization_id=? and review_status='PENDING') pending_classifications,
                  (select count(*) from customer_deadline_snapshots
                     where organization_id=? and computed_deadline<=now()) due_deadlines,
                  (select count(*) from customer_pool_entries
                     where organization_id=? and status='OPEN') open_pool,
                  (select count(*) from second_line_recruitments
                     where organization_id=? and status in ('VOLUNTEER_OPEN','ADMIN_PENDING')) pending_second_line,
                  (select count(*) from performance_interaction_claims
                     where organization_id=? and status='PENDING') pending_performance_claims,
                  (select count(*) from performance_activities
                     where organization_id=? and status in ('PROPOSED','COMPLETION_SUBMITTED')) pending_performance_activities,
                  (select count(*) from consultation_handoffs
                     where organization_id=? and status='AWAITING_FEEDBACK'
                       and coalesce(feedback_due_at,scheduled_end+interval '48 hours')<=now()) overdue_consultations,
                  (select count(*) from customer_analysis_jobs
                     where organization_id=? and status='FAILED') failed_intelligence
                """, (rs, row) -> new MobileDtos.AdminOverview(
                rs.getLong("pending_deals"), rs.getLong("pending_classifications"),
                rs.getLong("due_deadlines"), rs.getLong("open_pool"),
                rs.getLong("pending_second_line"), rs.getLong("pending_performance_claims"),
                rs.getLong("pending_performance_activities"), rs.getLong("overdue_consultations"),
                rs.getLong("failed_intelligence"), Instant.now()),
                actor.organizationId(), actor.organizationId(), actor.organizationId(), actor.organizationId(),
                actor.organizationId(), actor.organizationId(), actor.organizationId(), actor.organizationId(),
                actor.organizationId());
    }
}
