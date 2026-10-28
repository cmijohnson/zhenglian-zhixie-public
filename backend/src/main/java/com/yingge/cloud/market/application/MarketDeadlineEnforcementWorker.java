package com.yingge.cloud.market.application;

import com.yingge.cloud.customerpool.application.CustomerPoolService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MarketDeadlineEnforcementWorker {
    private final NamedParameterJdbcTemplate jdbc;
    private final CustomerPoolService customerPools;
    private final MarketDeadlineService deadlines;

    public MarketDeadlineEnforcementWorker(NamedParameterJdbcTemplate jdbc,
                                           CustomerPoolService customerPools,
                                           MarketDeadlineService deadlines) {
        this.jdbc = jdbc;
        this.customerPools = customerPools;
        this.deadlines = deadlines;
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Shanghai")
    @Transactional
    public int enforceDueDeadlines() {
        deadlines.refreshSnapshotsForActiveRules();
        List<Map<String, Object>> due = jdbc.queryForList("""
                select snapshot.organization_id, snapshot.customer_id, snapshot.cycle_id,
                       snapshot.rule_version_id, snapshot.computed_deadline
                from customer_deadline_snapshots snapshot
                join customers customer on customer.id=snapshot.customer_id
                  and customer.organization_id=snapshot.organization_id
                join organization_follow_up_policies policy
                  on policy.organization_id=snapshot.organization_id
                left join customer_ownership_states state on state.customer_id=snapshot.customer_id
                  and state.organization_id=snapshot.organization_id
                where snapshot.mode='ENFORCED' and snapshot.computed_deadline<=now()
                  and policy.ab_auto_release_enabled=true
                  and policy.ab_auto_release_effective_from is not null
                  and snapshot.computed_deadline>=policy.ab_auto_release_effective_from
                  and customer.archived_at is null
                  and (state.status is null or state.status='ASSIGNED')
                  and not exists(select 1 from customer_pool_entries entry
                    where entry.organization_id=snapshot.organization_id
                      and entry.customer_id=snapshot.customer_id
                      and entry.status in ('OPEN','TEMP_CLAIMED'))
                order by snapshot.computed_deadline
                for update of snapshot, customer skip locked
                """, Map.of());
        int released = 0;
        for (Map<String, Object> row : due) {
            UUID organizationId = (UUID) row.get("organization_id");
            UUID customerId = (UUID) row.get("customer_id");
            boolean pooled = customerPools.handleDeadlineExpiry(organizationId, customerId);
            jdbc.update("""
                    insert into customer_deadline_events (
                        id, organization_id, customer_id, cycle_id, event_type, applied, reason,
                        previous_deadline, calculated_deadline, rule_version_id
                    ) values (
                        :id, :organizationId, :customerId, :cycleId, :eventType,
                        true, :reason, :deadline, :deadline, :ruleId
                    )
                    """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                    .addValue("organizationId", organizationId).addValue("customerId", customerId)
                    .addValue("cycleId", row.get("cycle_id")).addValue("ruleId", row.get("rule_version_id"))
                    .addValue("eventType", pooled ? "DEADLINE_ENFORCED_RELEASE" : "DEADLINE_TAKEOVER_REQUIRED")
                    .addValue("reason", pooled ? "ENFORCED_DEADLINE_EXPIRED" : "ACTIVE_SECOND_LINE_REQUIRES_ADMIN")
                    .addValue("deadline", row.get("computed_deadline")));
            jdbc.update("""
                    delete from customer_deadline_snapshots
                    where organization_id=:organizationId and customer_id=:customerId
                      and mode='ENFORCED'
                    """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                    .addValue("customerId", customerId));
            released++;
        }
        return released;
    }

}
