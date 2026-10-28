package com.yingge.cloud.intelligence.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class CustomerIntelligenceQueue {
    private final JdbcTemplate jdbc;

    public CustomerIntelligenceQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void enqueue(UUID organizationId, UUID customerId, TriggerType trigger, UUID actorMembershipId) {
        SettingsFlag flag = jdbc.query("""
                select enabled, auto_profile_analysis, auto_timeline_analysis
                from customer_intelligence_settings where organization_id=?
                """, (row, index) -> new SettingsFlag(
                row.getBoolean("enabled"), row.getBoolean("auto_profile_analysis"),
                row.getBoolean("auto_timeline_analysis")), organizationId)
                .stream().findFirst().orElse(new SettingsFlag(true, true, true));
        if (!flag.enabled()
                || ((trigger == TriggerType.CUSTOMER_CREATED || trigger == TriggerType.PROFILE_UPDATED)
                    && !flag.profile())
                || (trigger == TriggerType.TIMELINE_UPDATED && !flag.timeline())) {
            return;
        }
        Instant availableAt = trigger == TriggerType.MANUAL ? Instant.now() : Instant.now().plusSeconds(30);
        int inserted = jdbc.update("""
                insert into customer_analysis_jobs
                  (id, organization_id, customer_id, trigger_type, triggered_by_membership_id, available_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict do nothing
                """, UUID.randomUUID(), organizationId, customerId, trigger.name(), actorMembershipId,
                Timestamp.from(availableAt));
        if (inserted == 0) {
            jdbc.update("""
                    update customer_analysis_jobs
                    set trigger_type=?, triggered_by_membership_id=coalesce(?, triggered_by_membership_id),
                        available_at=case when status='PENDING' then ? else available_at end,
                        attempts=case when status='PENDING' then 0 else attempts end,
                        last_error_code=case when status='PENDING' then null else last_error_code end,
                        last_error_message=case when status='PENDING' then null else last_error_message end,
                        rerun_requested=case when status='RUNNING' then true else rerun_requested end,
                        updated_at=now()
                    where organization_id=? and customer_id=? and status in ('PENDING','RUNNING')
                    """, trigger.name(), actorMembershipId, Timestamp.from(availableAt),
                    organizationId, customerId);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Job claimNext() {
        return jdbc.query("""
                with candidate as (
                  select id from customer_analysis_jobs
                  where (
                    status='PENDING' and available_at <= now()
                    or status='RUNNING' and locked_at < now() - interval '10 minutes'
                  ) and attempts < 3
                  order by available_at, created_at
                  for update skip locked
                  limit 1
                )
                update customer_analysis_jobs job
                set status='RUNNING', attempts=attempts+1, locked_at=now(),
                    rerun_requested=false, updated_at=now()
                from candidate
                where job.id=candidate.id
                returning job.id, job.organization_id, job.customer_id, job.trigger_type,
                          job.triggered_by_membership_id, job.attempts
                """, (row, index) -> new Job(
                row.getObject("id", UUID.class), row.getObject("organization_id", UUID.class),
                row.getObject("customer_id", UUID.class), TriggerType.valueOf(row.getString("trigger_type")),
                row.getObject("triggered_by_membership_id", UUID.class), row.getInt("attempts")))
                .stream().findFirst().orElse(null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(UUID jobId) {
        jdbc.update("""
                update customer_analysis_jobs
                set status=case when rerun_requested then 'PENDING' else 'SUCCEEDED' end,
                    available_at=case when rerun_requested then now() + interval '30 seconds' else available_at end,
                    attempts=case when rerun_requested then 0 else attempts end,
                    completed_at=case when rerun_requested then null else now() end,
                    locked_at=null, last_error_code=null, last_error_message=null, updated_at=now()
                where id=? and status='RUNNING'
                """, jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Job job, String code, String message) {
        boolean retry = job.attempts() < 3;
        jdbc.update("""
                update customer_analysis_jobs
                set status=?, available_at=?, locked_at=null, completed_at=?,
                    last_error_code=?, last_error_message=?, updated_at=now()
                where id=? and status='RUNNING'
                """, retry ? "PENDING" : "FAILED",
                Timestamp.from(Instant.now().plusSeconds(30L * job.attempts())),
                retry ? null : Timestamp.from(Instant.now()), code, abbreviate(message), job.id());
    }

    @Scheduled(cron = "0 * 0-5 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void seedNightlyBackfill() {
        List<BackfillOrganization> organizations = jdbc.query("""
                select organization_id, least(nightly_rate_per_minute, 10) batch_size
                from customer_intelligence_settings
                where enabled=true and backfill_enabled=true and backfill_paused=false
                """, (row, index) -> new BackfillOrganization(
                row.getObject("organization_id", UUID.class), row.getInt("batch_size")));
        for (BackfillOrganization organization : organizations) {
            List<UUID> customerIds = jdbc.queryForList("""
                    select customer.id
                    from customers customer
                    where customer.organization_id=? and customer.archived_at is null
                      and not exists (
                        select 1 from customer_analysis_versions version
                        where version.organization_id=customer.organization_id
                          and version.customer_id=customer.id and version.status='SUCCEEDED'
                      )
                      and not exists (
                        select 1 from customer_analysis_jobs job
                        where job.organization_id=customer.organization_id
                          and job.customer_id=customer.id and job.status in ('PENDING','RUNNING')
                      )
                    order by customer.created_at, customer.id
                    limit ?
                    """, UUID.class, organization.organizationId(), organization.batchSize());
            for (UUID customerId : customerIds) {
                enqueue(organization.organizationId(), customerId, TriggerType.BACKFILL, null);
            }
        }
    }

    private String abbreviate(String message) {
        if (message == null || message.isBlank()) return "分析任务失败";
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    public enum TriggerType { CUSTOMER_CREATED, PROFILE_UPDATED, TIMELINE_UPDATED, MANUAL, BACKFILL }

    public record Job(UUID id, UUID organizationId, UUID customerId, TriggerType trigger,
                      UUID actorMembershipId, int attempts) {}

    private record SettingsFlag(boolean enabled, boolean profile, boolean timeline) {}
    private record BackfillOrganization(UUID organizationId, int batchSize) {}
}
