package com.yingge.cloud.notification.api;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/governance")
public class GovernanceOverviewController {
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;

    public GovernanceOverviewController(JdbcTemplate jdbc, CurrentActorProvider actors,
                                        PermissionService permissions) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
    }

    @GetMapping("/overview")
    public Overview overview() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return jdbc.queryForObject("""
                select
                  (select count(*) from customer_deadline_snapshots
                     where organization_id=? and computed_deadline<=now()+interval '24 hours') deadline_due,
                  (select count(*) from customer_pool_entries
                     where organization_id=? and status='OPEN') pool_open,
                  (select count(*) from customer_pool_entries
                     where organization_id=? and status='TEMP_CLAIMED') temporary_claims,
                  (select count(*) from second_line_recruitments
                     where organization_id=? and status in ('VOLUNTEER_OPEN','ADMIN_PENDING')) second_line_required,
                  (select count(*) from second_line_contact_authorizations
                     where organization_id=? and status in ('PENDING_OWNER','ADMIN_PENDING')) contact_authorizations,
                  (select count(*) from customer_takeover_tasks
                     where organization_id=? and status='ADMIN_PENDING') takeover_tasks,
                  (select count(*) from customer_classification_review_queue
                     where organization_id=? and review_status='PENDING') classification_reviews,
                  (select count(*) from customer_business_cycles
                     where organization_id=? and status='WON_PENDING') won_pending,
                  (select count(*) from customer_business_cycles
                     where organization_id=? and status='LOCK_PENDING') quote_pending
                """, (rs, row) -> new Overview(
                rs.getLong("deadline_due"), rs.getLong("pool_open"), rs.getLong("temporary_claims"),
                rs.getLong("second_line_required"), rs.getLong("contact_authorizations"),
                rs.getLong("takeover_tasks"), rs.getLong("classification_reviews"),
                rs.getLong("won_pending"), rs.getLong("quote_pending")), actor.organizationId(), actor.organizationId(), actor.organizationId(),
                actor.organizationId(), actor.organizationId(), actor.organizationId(), actor.organizationId(),
                actor.organizationId(), actor.organizationId());
    }

    public record Overview(long deadlineDue, long poolOpen, long temporaryClaims, long secondLineRequired,
                           long contactAuthorizations, long takeoverTasks, long classificationReviews,
                           long wonPending, long quotePending) {}
}
