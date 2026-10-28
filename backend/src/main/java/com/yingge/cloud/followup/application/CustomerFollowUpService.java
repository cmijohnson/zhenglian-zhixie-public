package com.yingge.cloud.followup.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customerpool.application.CustomerPoolService;
import com.yingge.cloud.followup.api.FollowUpDtos;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerFollowUpService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final AuditService audit;
    private final CustomerPoolService customerPools;
    private final NotificationService notifications;

    public CustomerFollowUpService(NamedParameterJdbcTemplate jdbc, CurrentActorProvider actors, AuditService audit,
                                   CustomerPoolService customerPools, NotificationService notifications) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.audit = audit;
        this.customerPools = customerPools;
        this.notifications = notifications;
    }

    @Transactional
    public void reconcileCustomer(UUID organizationId, UUID customerId) {
        List<Map<String, Object>> state = jdbc.queryForList("""
                select customer.archived_at,
                       exists(select 1 from customer_tags ct join tags tag on tag.id=ct.tag_id
                              where ct.customer_id=customer.id and tag.category='客户质量'
                                and tag.name='A类用户' and tag.active=true) a_class,
                       exists(select 1 from memberships owner
                           join user_accounts owner_account on owner_account.id=owner.user_id and owner_account.enabled=true
                           where owner.id=customer.owner_membership_id and owner.status='ACTIVE') owner_active,
                       (select count(distinct follower.membership_id) from (
                            select customer.owner_membership_id membership_id
                            union all
                            select assignment.membership_id
                            from customer_role_assignments assignment
                            where assignment.organization_id=customer.organization_id
                              and assignment.customer_id=customer.id
                              and assignment.role_type='A_SECOND_LINE' and assignment.status='ACTIVE'
                       ) follower
                       join memberships membership on membership.id=follower.membership_id
                         and membership.organization_id=customer.organization_id and membership.status='ACTIVE'
                       join user_accounts account on account.id=membership.user_id and account.enabled=true)
                       follower_count
                from customers customer where customer.organization_id=:organizationId and customer.id=:customerId
                """, params("organizationId", organizationId, "customerId", customerId));
        if (state.isEmpty()) return;
        boolean activeAClass = state.getFirst().get("archived_at") == null
                && Boolean.TRUE.equals(state.getFirst().get("a_class"));
        boolean ownerActive = Boolean.TRUE.equals(state.getFirst().get("owner_active"));
        int followerCount = ((Number) state.getFirst().get("follower_count")).intValue();
        if (ownerActive) completeOwnerMissingReminders(organizationId, customerId);
        if (!activeAClass || (ownerActive && followerCount >= 2)) {
            jdbc.update("""
                    update follow_up_recruitments set status=case when :cancel then 'CANCELLED' else 'CLOSED' end,
                      closed_at=now(), updated_at=now(), version=version+1
                    where organization_id=:organizationId and customer_id=:customerId and status='OPEN'
                    """, params("cancel", !activeAClass, "organizationId", organizationId, "customerId", customerId));
            if (!activeAClass) completeOwnerMissingReminders(organizationId, customerId);
            return;
        }
        if (!ownerActive) createOwnerMissingReminders(organizationId, customerId);
        int needed = Math.max(ownerActive ? 0 : 1, 2 - followerCount);
        int updated = jdbc.update("""
                update follow_up_recruitments set required_count=:needed,
                  claimed_count=least(claimed_count, :needed), updated_at=now(), version=version+1
                where organization_id=:organizationId and customer_id=:customerId and status='OPEN'
                """, params("needed", needed, "organizationId", organizationId, "customerId", customerId));
        if (updated == 0) {
            jdbc.update("""
                    insert into follow_up_recruitments
                      (id, organization_id, customer_id, required_count, claimed_count, status)
                    values (:id, :organizationId, :customerId, :needed, 0, 'OPEN')
                    """, params("id", UUID.randomUUID(), "organizationId", organizationId,
                    "customerId", customerId, "needed", needed));
        }
    }

    @Transactional(readOnly = true)
    public List<FollowUpDtos.Recruitment> openRecruitments() {
        CurrentActor actor = requireWritableActor();
        return jdbc.query("""
                select recruitment.id, recruitment.customer_id, customer.full_name, customer.phone,
                       customer.wechat_id, customer.qq_id, customer.school, customer.major, customer.grade,
                       customer.high_school, customer.source_location, customer.demand_summary,
                       account.display_name owner_display_name, recruitment.required_count,
                       recruitment.claimed_count, recruitment.opened_at
                from follow_up_recruitments recruitment
                join customers customer on customer.id=recruitment.customer_id
                  and customer.organization_id=recruitment.organization_id and customer.archived_at is null
                join memberships owner on owner.id=customer.owner_membership_id
                join user_accounts account on account.id=owner.user_id
                where recruitment.organization_id=:organizationId and recruitment.status='OPEN'
                  and recruitment.opened_at>now()-interval '24 hours'
                  and customer.owner_membership_id<>:membershipId
                  and not exists(select 1 from customer_access_grants grant_row
                      where grant_row.customer_id=customer.id and grant_row.grantee_membership_id=:membershipId
                        and grant_row.can_edit=true)
                order by recruitment.opened_at
                """, params("organizationId", actor.organizationId(), "membershipId", actor.membershipId()),
                (row, index) -> new FollowUpDtos.Recruitment(
                        row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
                        "重点客户", null, null,
                        null, row.getString("school"), null,
                        row.getString("grade"), row.getString("high_school"), row.getString("source_location"),
                        row.getString("demand_summary"), null,
                        row.getInt("required_count"), row.getInt("claimed_count"),
                        row.getTimestamp("opened_at").toInstant(), List.of()));
    }

    @Transactional
    public FollowUpDtos.ClaimResult claim(UUID recruitmentId) {
        CurrentActor actor = requireWritableActor();
        List<Map<String, Object>> locked = jdbc.queryForList("""
                select id, customer_id, required_count, claimed_count
                from follow_up_recruitments
                where id=:id and organization_id=:organizationId and status='OPEN'
                  and opened_at>now()-interval '24 hours'
                for update
                """, params("id", recruitmentId, "organizationId", actor.organizationId()));
        if (locked.isEmpty()) throw ApiException.conflict("RECRUITMENT_CLOSED", "该协作招募已结束");
        UUID customerId = (UUID) locked.getFirst().get("customer_id");
        List<Map<String, Object>> ownerRows = jdbc.queryForList("""
                select customer.owner_membership_id,
                  (membership.status='ACTIVE' and account.enabled=true) owner_active
                from customers customer join memberships membership on membership.id=customer.owner_membership_id
                join user_accounts account on account.id=membership.user_id
                where customer.id=:customerId and customer.organization_id=:organizationId
                  and customer.archived_at is null
                """, params("customerId", customerId, "organizationId", actor.organizationId()));
        if (ownerRows.isEmpty()) {
            throw ApiException.conflict("RECRUITMENT_CLOSED", "该客户已归档或协作招募已失效");
        }
        Map<String, Object> ownerState = ownerRows.getFirst();
        UUID owner = (UUID) ownerState.get("owner_membership_id");
        if (actor.membershipId().equals(owner)) {
            throw ApiException.badRequest("OWNER_CANNOT_CLAIM", "主负责人无需领取自己的客户");
        }
        if (!Boolean.TRUE.equals(ownerState.get("owner_active"))) {
            throw ApiException.conflict("OWNER_ASSIGNMENT_REQUIRED", "该客户主负责人已停用，请管理员先完成负责人转接");
        }
        Integer followerCount = jdbc.queryForObject("""
                select count(distinct follower.membership_id) from (
                  select :ownerId membership_id
                  union all
                  select assignment.membership_id from customer_role_assignments assignment
                  where assignment.organization_id=:organizationId and assignment.customer_id=:customerId
                    and assignment.role_type='A_SECOND_LINE' and assignment.status='ACTIVE'
                ) follower join memberships membership on membership.id=follower.membership_id
                  and membership.organization_id=:organizationId and membership.status='ACTIVE'
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                """, params("ownerId", owner, "organizationId", actor.organizationId(), "customerId", customerId),
                Integer.class);
        if (followerCount != null && followerCount >= 2) {
            jdbc.update("""
                    update follow_up_recruitments set status='CLOSED', closed_at=now(),
                      updated_at=now(), version=version+1 where id=:id
                    """, params("id", recruitmentId));
            throw ApiException.conflict("RECRUITMENT_FULL", "该客户已满足双人跟进要求");
        }
        try {
            jdbc.update("""
                    insert into follow_up_recruitment_claims
                      (id, organization_id, recruitment_id, customer_id, membership_id)
                    values (:id, :organizationId, :recruitmentId, :customerId, :membershipId)
                    """, params("id", UUID.randomUUID(), "organizationId", actor.organizationId(),
                    "recruitmentId", recruitmentId, "customerId", customerId,
                    "membershipId", actor.membershipId()));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("RECRUITMENT_ALREADY_CLAIMED", "你已经是该客户的跟进成员");
        }
        jdbc.update("""
                insert into customer_access_grants
                  (id, organization_id, customer_id, grantee_membership_id, can_edit, granted_by_membership_id)
                values (:id, :organizationId, :customerId, :membershipId, true, :membershipId)
                on conflict (organization_id, customer_id, grantee_membership_id)
                  where grantee_membership_id is not null do update set can_edit=true
                """, params("id", UUID.randomUUID(), "organizationId", actor.organizationId(),
                "customerId", customerId, "membershipId", actor.membershipId()));
        try {
            jdbc.update("""
                    insert into customer_role_assignments
                      (id, organization_id, customer_id, membership_id, role_type, status,
                       assigned_by_membership_id, assignment_source)
                    values (:id,:organizationId,:customerId,:membershipId,'A_SECOND_LINE','ACTIVE',
                       :membershipId,'LEGACY_RECRUITMENT')
                    """, params("id", UUID.randomUUID(), "organizationId", actor.organizationId(),
                    "customerId", customerId, "membershipId", actor.membershipId()));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("SECOND_LINE_ALREADY_ASSIGNED", "你已经是该客户的二线");
        }
        int required = ((Number) locked.getFirst().get("required_count")).intValue();
        int claimed = ((Number) locked.getFirst().get("claimed_count")).intValue() + 1;
        boolean closed = claimed >= required;
        jdbc.update("""
                update follow_up_recruitments set claimed_count=:claimed,
                  status=case when :closed then 'CLOSED' else 'OPEN' end,
                  closed_at=case when :closed then now() else null end,
                  updated_at=now(), version=version+1 where id=:id
                """, params("claimed", claimed, "closed", closed, "id", recruitmentId));
        audit.record(actor, "FOLLOW_UP_RECRUITMENT_CLAIMED", "CUSTOMER", customerId.toString(), "SUCCESS",
                Map.of("recruitmentId", recruitmentId.toString()));
        customerPools.reconcileClassification(actor.organizationId(), customerId, "A", actor.membershipId());
        reconcileCustomer(actor.organizationId(), customerId);
        return new FollowUpDtos.ClaimResult(recruitmentId, customerId, Math.max(0, required - claimed), closed);
    }

    @Transactional(readOnly = true)
    public FollowUpDtos.Policy policy() {
        CurrentActor actor = requireAdmin();
        return loadPolicy(actor.organizationId());
    }

    @Transactional
    public FollowUpDtos.Policy updatePolicy(FollowUpDtos.UpdatePolicy body) {
        CurrentActor actor = requireAdmin();
        int changed = jdbc.update("""
                update organization_follow_up_policies
                set a_class_stale_enabled=:enabled, stale_after_hours=:hours, notify_at=:notifyAt,
                    qq_enabled=:qqEnabled, updated_by_membership_id=:membershipId,
                    ab_auto_release_effective_from=case
                      when :abReleaseEnabled and not ab_auto_release_enabled then now()
                      when not :abReleaseEnabled then null
                      else ab_auto_release_effective_from end,
                    ab_auto_release_enabled=:abReleaseEnabled,
                    updated_at=now(), version=version+1
                where organization_id=:organizationId and version=:version
                """, params("enabled", body.aClassStaleEnabled(), "hours", body.staleAfterHours(),
                "notifyAt", Time.valueOf(body.notifyAt()), "qqEnabled", body.qqEnabled(),
                "abReleaseEnabled", body.abAutoReleaseEnabled(),
                "membershipId", actor.membershipId(), "organizationId", actor.organizationId(),
                "version", body.expectedVersion()));
        if (changed == 0) throw ApiException.conflict("STALE_VERSION", "策略已被修改，请刷新后重试");
        audit.record(actor, "FOLLOW_UP_POLICY_UPDATED", "ORGANIZATION", actor.organizationId().toString(),
                "SUCCESS", Map.of("staleAfterHours", body.staleAfterHours(), "qqEnabled", body.qqEnabled(),
                        "abAutoReleaseEnabled", body.abAutoReleaseEnabled()));
        return loadPolicy(actor.organizationId());
    }

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Shanghai")
    @Transactional
    public void createStaleReminders() {
        LocalTime now = LocalTime.now(BUSINESS_ZONE);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<Map<String, Object>> organizations = jdbc.queryForList("""
                select organization_id, stale_after_hours, notify_at
                from organization_follow_up_policies where a_class_stale_enabled=true
                """, Map.of());
        for (Map<String, Object> policy : organizations) {
            LocalTime notifyAt = ((Time) policy.get("notify_at")).toLocalTime();
            if (now.isBefore(notifyAt)) continue;
            UUID organizationId = (UUID) policy.get("organization_id");
            int hours = ((Number) policy.get("stale_after_hours")).intValue();
            createStaleReminders(organizationId, hours, today, null);
        }
    }

    @Transactional
    public void createStaleRemindersForCurrentActor() {
        CurrentActor actor = actors.require();
        List<Map<String, Object>> policy = jdbc.queryForList("""
                select stale_after_hours from organization_follow_up_policies
                where organization_id=:organizationId and a_class_stale_enabled=true
                """, params("organizationId", actor.organizationId()));
        if (policy.isEmpty()) return;
        int hours = ((Number) policy.getFirst().get("stale_after_hours")).intValue();
        createStaleReminders(actor.organizationId(), hours, LocalDate.now(BUSINESS_ZONE), actor.membershipId());
    }

    private void createStaleReminders(UUID organizationId, int hours, LocalDate today, UUID membershipId) {
        List<Map<String, Object>> assignees = jdbc.queryForList("""
                with stale_customers as (
                  select customer.id, customer.full_name, customer.owner_membership_id
                  from customers customer
                  where customer.organization_id=:organizationId and customer.archived_at is null
                    and coalesce(customer.last_contact_at, customer.created_at) < now() - make_interval(hours => :hours)
                    and exists(select 1 from customer_tags ct join tags tag on tag.id=ct.tag_id
                      where ct.customer_id=customer.id and tag.category='客户质量'
                        and tag.name='A类用户' and tag.active=true)
                ), assignees as (
                  select id customer_id, full_name, owner_membership_id membership_id from stale_customers
                  union
                  select stale.id, stale.full_name, grant_row.grantee_membership_id
                  from stale_customers stale join customer_access_grants grant_row
                    on grant_row.customer_id=stale.id and grant_row.can_edit=true
                )
                select assignees.customer_id, assignees.full_name, assignees.membership_id
                from assignees join memberships membership on membership.id=assignees.membership_id
                  and membership.organization_id=:organizationId and membership.status='ACTIVE'
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                where (cast(:membershipId as uuid) is null or assignees.membership_id=:membershipId)
                """, params("organizationId", organizationId, "hours", hours, "membershipId", membershipId));
        for (Map<String, Object> item : assignees) {
            jdbc.update("""
                    insert into reminders
                      (id, organization_id, customer_id, assigned_membership_id, remind_at, title,
                       status, delivery_status, reminder_type, automatic_day, related_resource_id)
                    values (:id, :organizationId, :customerId, :membershipId, now(), :title,
                       'PENDING', 'UNSENT', 'A_CLASS_STALE', :automaticDay, :customerId)
                    on conflict (organization_id, assigned_membership_id, reminder_type, related_resource_id)
                      where reminder_type<>'MANUAL' and status='PENDING' and related_resource_id is not null
                    do update set remind_at=excluded.remind_at, title=excluded.title,
                      automatic_day=excluded.automatic_day, delivery_status='UNSENT', updated_at=now()
                    where reminders.automatic_day is distinct from excluded.automatic_day
                    """, params("id", UUID.randomUUID(), "organizationId", organizationId,
                    "customerId", item.get("customer_id"), "membershipId", item.get("membership_id"),
                    "title", "A 类客户已超过 " + hours + " 小时未更新：" + item.get("full_name"),
                    "automaticDay", today));
            notifications.publish(organizationId, List.of((UUID) item.get("membership_id")),
                    "A_CLASS_STALE", "A 类客户跟进超时",
                    "一名 A 类客户已超过 " + hours + " 小时没有有效时间线更新，请及时处理。",
                    "/customers/" + item.get("customer_id"),
                    "a-class-stale:" + item.get("customer_id") + ":" + today);
        }
    }

    @Scheduled(cron = "0 */30 * * * *", zone = "Asia/Shanghai")
    @Transactional
    public void reconcileAllAClassCustomers() {
        List<Map<String, Object>> customers = jdbc.queryForList("""
                select distinct customer.organization_id, customer.id
                from customers customer
                join customer_tags customer_tag on customer_tag.customer_id=customer.id
                join tags tag on tag.id=customer_tag.tag_id and tag.active=true
                where customer.archived_at is null and tag.category='客户质量' and tag.name='A类用户'
                """, Map.of());
        for (Map<String, Object> customer : customers) {
            reconcileCustomer((UUID) customer.get("organization_id"), (UUID) customer.get("id"));
        }
    }

    @Transactional
    public void completeStaleReminders(UUID organizationId, UUID customerId) {
        jdbc.update("""
                update reminders set status='COMPLETED', completed_at=now(), updated_at=now()
                where organization_id=:organizationId and customer_id=:customerId
                  and reminder_type='A_CLASS_STALE' and status='PENDING'
                """, params("organizationId", organizationId, "customerId", customerId));
    }

    private void createOwnerMissingReminders(UUID organizationId, UUID customerId) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<UUID> admins = jdbc.queryForList("""
                select distinct membership.id from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                join membership_roles membership_role on membership_role.membership_id=membership.id
                join roles role on role.id=membership_role.role_id and role.code='ORG_ADMIN'
                where membership.organization_id=:organizationId and membership.status='ACTIVE'
                """, params("organizationId", organizationId), UUID.class);
        for (UUID admin : admins) {
            jdbc.update("""
                    insert into reminders
                      (id, organization_id, customer_id, assigned_membership_id, remind_at, title,
                       status, delivery_status, reminder_type, automatic_day, related_resource_id)
                    values (:id, :organizationId, :customerId, :admin, now(),
                      'A 类客户缺少有效主负责人，请尽快转接', 'PENDING', 'UNSENT',
                      'A_CLASS_OWNER_MISSING', :day, :customerId)
                    on conflict (organization_id, assigned_membership_id, reminder_type, related_resource_id)
                      where reminder_type<>'MANUAL' and status='PENDING' and related_resource_id is not null
                    do update set remind_at=excluded.remind_at, title=excluded.title,
                      automatic_day=excluded.automatic_day, delivery_status='UNSENT', updated_at=now()
                    where reminders.automatic_day is distinct from excluded.automatic_day
                    """, params("id", UUID.randomUUID(), "organizationId", organizationId,
                    "customerId", customerId, "admin", admin, "day", today));
        }
    }

    private void completeOwnerMissingReminders(UUID organizationId, UUID customerId) {
        jdbc.update("""
                update reminders set status='COMPLETED', completed_at=now(), updated_at=now()
                where organization_id=:organizationId and customer_id=:customerId
                  and reminder_type='A_CLASS_OWNER_MISSING' and status='PENDING'
                """, params("organizationId", organizationId, "customerId", customerId));
    }

    private List<FollowUpDtos.TimelineItem> recentTimeline(UUID customerId) {
        return jdbc.query("""
                select contacted_at, channel, summary, details, outcome from interactions
                where customer_id=:customerId order by contacted_at desc limit 8
                """, params("customerId", customerId), (row, index) -> new FollowUpDtos.TimelineItem(
                row.getTimestamp("contacted_at").toInstant(), row.getString("channel"), row.getString("summary"),
                row.getString("details"), row.getString("outcome")));
    }

    private FollowUpDtos.Policy loadPolicy(UUID organizationId) {
        return jdbc.queryForObject("""
                select a_class_stale_enabled, stale_after_hours, notify_at, qq_enabled,
                       ab_auto_release_enabled, ab_auto_release_effective_from, version
                from organization_follow_up_policies where organization_id=:organizationId
                """, params("organizationId", organizationId), (row, index) -> new FollowUpDtos.Policy(
                row.getBoolean("a_class_stale_enabled"), row.getInt("stale_after_hours"),
                row.getTime("notify_at").toLocalTime(), row.getBoolean("qq_enabled"),
                row.getBoolean("ab_auto_release_enabled"),
                row.getTimestamp("ab_auto_release_effective_from") == null ? null
                        : row.getTimestamp("ab_auto_release_effective_from").toInstant(),
                row.getLong("version")));
    }

    private CurrentActor requireWritableActor() {
        CurrentActor actor = actors.require();
        if (!actor.canWriteCustomers()) throw ApiException.forbidden("只读账号不能领取客户协作");
        return actor;
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin()) throw ApiException.forbidden("只有组织管理员可以维护跟进策略");
        return actor;
    }

    private MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource result = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) {
            result.addValue((String) values[index], values[index + 1]);
        }
        return result;
    }
}
