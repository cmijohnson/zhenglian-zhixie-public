package com.yingge.cloud.customerpool.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customerpool.api.CustomerPoolDtos;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CustomerPoolService {
    private static final Set<String> OWNERSHIP_ACTIONS = Set.of("CUSTOMER_REPLY", "OUTBOUND_NO_REPLY");

    public enum PoolType { AB, C }
    public enum ReleaseReason { DEADLINE_EXPIRED, VOLUNTARY_RELEASE, MEMBER_DEPARTURE, C_CLASSIFIED, CLAIM_EXPIRED, OWNER_INACTIVE }

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final AuditService audit;
    private final NotificationService notifications;

    public CustomerPoolService(NamedParameterJdbcTemplate jdbc, CurrentActorProvider actors, AuditService audit,
                               NotificationService notifications) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.audit = audit;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<CustomerPoolDtos.PoolEntry> list(PoolType requestedType) {
        CurrentActor actor = requireWritableActor();
        String typePredicate = requestedType == null ? "" : " and entry.pool_type=:poolType ";
        var parameters = params("organizationId", actor.organizationId(), "membershipId", actor.membershipId());
        if (requestedType != null) parameters.addValue("poolType", requestedType.name());
        return jdbc.query("""
                select entry.id, entry.customer_id, entry.pool_type, entry.release_reason,
                       customer.full_name customer_name, customer.school, customer.grade,
                       coalesce(source_dictionary.label,
                                nullif(btrim(customer.source_channel_legacy), ''),
                                '未填写来源') source,
                       customer.demand_summary,
                       entry.available_at, entry.priority_until, entry.status,
                       (entry.available_at<=now() and (entry.priority_owner_membership_id is null
                         or entry.priority_until<=now() or entry.priority_owner_membership_id=:membershipId)
                         and not exists(select 1 from customer_claim_cooldowns cooldown
                           where cooldown.organization_id=entry.organization_id
                             and cooldown.customer_id=entry.customer_id
                             and cooldown.membership_id=:membershipId
                             and cooldown.cooldown_until>now())) can_claim
                from customer_pool_entries entry
                join customers customer on customer.id=entry.customer_id
                  and customer.organization_id=entry.organization_id and customer.archived_at is null
                left join dictionary_items source_dictionary
                  on source_dictionary.id=customer.source_dictionary_id
                 and source_dictionary.organization_id=entry.organization_id
                where entry.organization_id=:organizationId and entry.status='OPEN'
                """ + typePredicate + " order by entry.available_at, entry.opened_at", parameters,
                (row, index) -> new CustomerPoolDtos.PoolEntry(
                        row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
                        row.getString("pool_type"), row.getString("release_reason"),
                        row.getString("customer_name"), row.getString("school"), row.getString("grade"), row.getString("source"),
                        row.getString("demand_summary"), maskedTimeline(row.getObject("customer_id", UUID.class)),
                        instant(row.getTimestamp("available_at")), instant(row.getTimestamp("priority_until")),
                        row.getBoolean("can_claim"), row.getString("status")));
    }

    @Transactional
    public UUID release(UUID customerId, CustomerPoolDtos.ReleaseRequest request) {
        CurrentActor actor = requireWritableActor();
        Map<String, Object> customer = lockCustomer(actor.organizationId(), customerId);
        UUID ownerId = (UUID) customer.get("owner_membership_id");
        if (!actor.isAdmin() && !actor.membershipId().equals(ownerId)) {
            throw ApiException.forbidden("只有主负责人或组织管理员可以主动释放客户");
        }
        String requested = request.reason().trim().toUpperCase();
        ReleaseReason reason;
        try {
            reason = ReleaseReason.valueOf(requested);
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("INVALID_RELEASE_REASON", "客户释放原因无效");
        }
        UUID entryId = releaseLocked(actor.organizationId(), customer, qualityPoolType(actor.organizationId(), customerId),
                reason, actor.membershipId(), TextNormalizer.optional(request.note()));
        audit.record(actor, "CUSTOMER_RELEASED_TO_POOL", "CUSTOMER", customerId.toString(), "SUCCESS",
                Map.of("poolEntryId", entryId.toString(), "reason", reason.name()));
        return entryId;
    }

    @Transactional
    public UUID releaseToPool(UUID organizationId, UUID customerId, PoolType type, ReleaseReason reason,
                              UUID actorMembershipId) {
        Map<String, Object> customer = lockCustomer(organizationId, customerId);
        return releaseLocked(organizationId, customer, type, reason, actorMembershipId, null);
    }

    @Transactional
    public CustomerPoolDtos.ClaimResult claim(UUID entryId, CustomerPoolDtos.ClaimRequest request) {
        CurrentActor actor = requireWritableActor();
        String idempotencyKey = request.idempotencyKey().trim();
        List<CustomerPoolDtos.ClaimResult> replay = jdbc.query("""
                select id, pool_entry_id, customer_id, status, claimed_at, expires_at
                from customer_claim_records
                where organization_id=:organizationId and claimant_membership_id=:membershipId
                  and idempotency_key=:idempotencyKey
                """, params("organizationId", actor.organizationId(), "membershipId", actor.membershipId(),
                        "idempotencyKey", idempotencyKey),
                (row, index) -> claimResult(row, true));
        if (!replay.isEmpty()) return replay.getFirst();

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select entry.*, customer.owner_membership_id
                from customer_pool_entries entry
                join customers customer on customer.id=entry.customer_id
                  and customer.organization_id=entry.organization_id and customer.archived_at is null
                where entry.id=:entryId and entry.organization_id=:organizationId
                for update of entry
                """, params("entryId", entryId, "organizationId", actor.organizationId()));
        if (rows.isEmpty() || !"OPEN".equals(rows.getFirst().get("status"))) {
            throw ApiException.conflict("POOL_ENTRY_UNAVAILABLE", "该客户已被认领或不再开放");
        }
        Map<String, Object> entry = rows.getFirst();
        if (instant((Timestamp) entry.get("available_at")).isAfter(databaseNow())) {
            throw ApiException.conflict("POOL_ENTRY_NOT_AVAILABLE", "该客户尚未开放认领");
        }
        UUID priorityOwner = (UUID) entry.get("priority_owner_membership_id");
        Instant priorityUntil = instant((Timestamp) entry.get("priority_until"));
        if (priorityOwner != null && priorityUntil != null && priorityUntil.isAfter(databaseNow())
                && !priorityOwner.equals(actor.membershipId())) {
            throw ApiException.conflict("PRIORITY_CLAIM_WINDOW", "该客户仍处于最初录入人的优先接手窗口");
        }
        Integer cooling = jdbc.queryForObject("""
                select count(*) from customer_claim_cooldowns where organization_id=:organizationId
                  and customer_id=:customerId and membership_id=:membershipId and cooldown_until>now()
                """, params("organizationId", actor.organizationId(), "customerId", entry.get("customer_id"),
                        "membershipId", actor.membershipId()), Integer.class);
        if (cooling != null && cooling > 0) {
            throw ApiException.conflict("CLAIM_COOLDOWN_ACTIVE", "临时认领失败后48小时内不能再次认领该客户");
        }

        UUID claimId = UUID.randomUUID();
        Map<String, Object> inserted;
        try {
            inserted = jdbc.queryForMap("""
                    insert into customer_claim_records
                      (id, organization_id, pool_entry_id, customer_id, claimant_membership_id,
                       status, idempotency_key, expires_at)
                    values (:id, :organizationId, :entryId, :customerId, :membershipId,
                       'TEMPORARY', :idempotencyKey, now()+interval '24 hours')
                    returning id, pool_entry_id, customer_id, status, claimed_at, expires_at
                    """, params("id", claimId, "organizationId", actor.organizationId(), "entryId", entryId,
                    "customerId", entry.get("customer_id"), "membershipId", actor.membershipId(),
                    "idempotencyKey", idempotencyKey));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("POOL_ENTRY_ALREADY_CLAIMED", "该客户已被其他成员认领");
        }
        jdbc.update("""
                update customer_pool_entries set status='TEMP_CLAIMED',
                  temporary_claim_membership_id=:membershipId,
                  temporary_claim_expires_at=:expiresAt, updated_at=now(), version=version+1
                where id=:entryId
                """, params("membershipId", actor.membershipId(), "expiresAt", inserted.get("expires_at"),
                "entryId", entryId));
        jdbc.update("""
                insert into customer_ownership_states
                  (customer_id, organization_id, status, active_pool_entry_id, previous_owner_membership_id,
                   temporary_claim_membership_id, temporary_claim_expires_at, ownership_cycle)
                values (:customerId, :organizationId, 'TEMP_CLAIMED', :entryId, :previousOwner,
                   :membershipId, :expiresAt, 1)
                on conflict (customer_id) do update set status='TEMP_CLAIMED', active_pool_entry_id=:entryId,
                  previous_owner_membership_id=:previousOwner, temporary_claim_membership_id=:membershipId,
                  temporary_claim_expires_at=:expiresAt, updated_at=now(), version=customer_ownership_states.version+1
                """, params("customerId", entry.get("customer_id"), "organizationId", actor.organizationId(),
                "entryId", entryId, "previousOwner", entry.get("previous_owner_membership_id"),
                "membershipId", actor.membershipId(), "expiresAt", inserted.get("expires_at")));
        upsertGrant(actor.organizationId(), (UUID) entry.get("customer_id"), actor.membershipId(), true,
                actor.membershipId());
        ownershipEvent(actor.organizationId(), (UUID) entry.get("customer_id"), "TEMPORARY_CLAIM_STARTED",
                actor.membershipId(), "OPEN", "TEMP_CLAIMED", entryId);
        notifications.publish(actor.organizationId(), recipients(actor.organizationId(),
                        (UUID) entry.get("previous_owner_membership_id"), actor.membershipId()),
                "CUSTOMER_TEMP_CLAIMED", "客户进入临时认领",
                "一名脱敏客户已进入24小时临时认领期。", "/customer-pools",
                "pool-temp-claim:" + claimId);
        audit.record(actor, "CUSTOMER_TEMPORARILY_CLAIMED", "CUSTOMER",
                entry.get("customer_id").toString(), "SUCCESS", Map.of("poolEntryId", entryId.toString()));
        return new CustomerPoolDtos.ClaimResult((UUID) inserted.get("id"), (UUID) inserted.get("pool_entry_id"),
                (UUID) inserted.get("customer_id"), (String) inserted.get("status"),
                instant((Timestamp) inserted.get("claimed_at")), instant((Timestamp) inserted.get("expires_at")), false);
    }

    @Transactional
    public boolean confirmTemporaryClaim(UUID customerId, UUID interactionId, String interactionType) {
        if (!OWNERSHIP_ACTIONS.contains(interactionType)) return false;
        CurrentActor actor = actors.require();
        List<Map<String, Object>> claims = jdbc.queryForList("""
                select claim.id, claim.pool_entry_id, claim.expires_at,
                       entry.previous_owner_membership_id
                from customer_claim_records claim
                join customer_pool_entries entry on entry.id=claim.pool_entry_id
                where claim.organization_id=:organizationId and claim.customer_id=:customerId
                  and claim.claimant_membership_id=:membershipId and claim.status='TEMPORARY'
                  and claim.expires_at>now()
                for update of claim, entry
                """, params("organizationId", actor.organizationId(), "customerId", customerId,
                "membershipId", actor.membershipId()));
        if (claims.isEmpty()) return false;
        Map<String, Object> claim = claims.getFirst();
        UUID previousOwner = (UUID) claim.get("previous_owner_membership_id");
        UUID teamId = firstActiveTeam(actor.membershipId());
        jdbc.update("""
                update customers set owner_membership_id=:membershipId, team_id=:teamId,
                  updated_at=now(), version=version+1
                where id=:customerId and organization_id=:organizationId and archived_at is null
                """, params("membershipId", actor.membershipId(), "teamId", teamId,
                "customerId", customerId, "organizationId", actor.organizationId()));
        jdbc.update("""
                update customer_claim_records set status='CONFIRMED', confirmed_at=now(),
                  confirming_interaction_id=:interactionId, ended_at=now() where id=:claimId
                """, params("interactionId", interactionId, "claimId", claim.get("id")));
        jdbc.update("""
                update customer_pool_entries set status='CLOSED', closed_at=now(), updated_at=now(),
                  temporary_claim_membership_id=null, temporary_claim_expires_at=null, version=version+1
                where id=:entryId
                """, params("entryId", claim.get("pool_entry_id")));
        jdbc.update("""
                update customer_ownership_states set status='ASSIGNED', active_pool_entry_id=null,
                  temporary_claim_membership_id=null, temporary_claim_expires_at=null,
                  ownership_cycle=ownership_cycle+1, updated_at=now(), version=version+1
                where customer_id=:customerId and organization_id=:organizationId
                """, params("customerId", customerId, "organizationId", actor.organizationId()));
        jdbc.update("""
                delete from customer_access_grants where organization_id=:organizationId
                  and customer_id=:customerId and grantee_membership_id=:membershipId
                """, params("organizationId", actor.organizationId(), "customerId", customerId,
                "membershipId", actor.membershipId()));
        if (previousOwner != null && !previousOwner.equals(actor.membershipId())) {
            upsertGrant(actor.organizationId(), customerId, previousOwner, false, actor.membershipId());
            jdbc.update("""
                    update reminders set assigned_membership_id=:newOwner, updated_at=now(), version=version+1
                    where organization_id=:organizationId and customer_id=:customerId
                      and assigned_membership_id=:previousOwner and status='PENDING'
                    """, params("newOwner", actor.membershipId(), "organizationId", actor.organizationId(),
                    "customerId", customerId, "previousOwner", previousOwner));
        }
        ownershipEvent(actor.organizationId(), customerId, "TEMPORARY_CLAIM_CONFIRMED", actor.membershipId(),
                "TEMP_CLAIMED", "ASSIGNED", (UUID) claim.get("pool_entry_id"));
        audit.record(actor, "CUSTOMER_CLAIM_CONFIRMED", "CUSTOMER", customerId.toString(), "SUCCESS",
                Map.of("interactionId", interactionId.toString()));
        return true;
    }

    @Transactional(readOnly = true)
    public boolean mayWriteInCurrentOwnershipState(UUID organizationId, UUID customerId,
                                                   UUID membershipId, boolean admin) {
        if (admin) return true;
        List<Map<String, Object>> states = jdbc.queryForList("""
                select status, temporary_claim_membership_id from customer_ownership_states
                where organization_id=:organizationId and customer_id=:customerId
                """, params("organizationId", organizationId, "customerId", customerId));
        if (states.isEmpty() || "ASSIGNED".equals(states.getFirst().get("status"))) return true;
        return "TEMP_CLAIMED".equals(states.getFirst().get("status"))
                && membershipId.equals(states.getFirst().get("temporary_claim_membership_id"));
    }

    @Transactional
    public void beginOwnershipCycle(UUID organizationId, UUID customerId, UUID previousOwner,
                                    UUID newOwner, UUID actorMembershipId, String source) {
        List<Map<String, Object>> activeClaims = jdbc.queryForList("""
                select claim.id, claim.pool_entry_id, claim.claimant_membership_id
                from customer_claim_records claim
                where claim.organization_id=:organizationId and claim.customer_id=:customerId
                  and claim.status='TEMPORARY' for update
                """, params("organizationId", organizationId, "customerId", customerId));
        for (Map<String, Object> claim : activeClaims) {
            jdbc.update("update customer_claim_records set status='CANCELLED', ended_at=now() where id=:id",
                    params("id", claim.get("id")));
            jdbc.update("""
                    delete from customer_access_grants where organization_id=:organizationId
                      and customer_id=:customerId and grantee_membership_id=:membershipId
                    """, params("organizationId", organizationId, "customerId", customerId,
                    "membershipId", claim.get("claimant_membership_id")));
        }
        jdbc.update("""
                update customer_pool_entries set status='CANCELLED', closed_at=now(), updated_at=now(),
                  temporary_claim_membership_id=null, temporary_claim_expires_at=null, version=version+1
                where organization_id=:organizationId and customer_id=:customerId
                  and status in ('OPEN','TEMP_CLAIMED')
                """, params("organizationId", organizationId, "customerId", customerId));
        jdbc.update("""
                insert into customer_ownership_states
                  (customer_id, organization_id, status, previous_owner_membership_id, ownership_cycle)
                values (:customerId,:organizationId,'ASSIGNED',:previousOwner,1)
                on conflict (customer_id) do update set status='ASSIGNED', active_pool_entry_id=null,
                  previous_owner_membership_id=:previousOwner, temporary_claim_membership_id=null,
                  temporary_claim_expires_at=null, ownership_cycle=customer_ownership_states.ownership_cycle+1,
                  updated_at=now(), version=customer_ownership_states.version+1
                """, params("customerId", customerId, "organizationId", organizationId,
                "previousOwner", previousOwner));
        ownershipEvent(organizationId, customerId, source, actorMembershipId,
                "ASSIGNED", "ASSIGNED", null);
    }

    @Transactional
    public void reconcileClassification(UUID organizationId, UUID customerId, String quality,
                                        UUID actorMembershipId) {
        String normalized = quality == null ? "" : quality.trim().toUpperCase();
        if (normalized.startsWith("C")) {
            releaseToPool(organizationId, customerId, PoolType.C, ReleaseReason.C_CLASSIFIED, actorMembershipId);
            closeSecondLineAssignments(organizationId, customerId, "RECLASSIFIED");
            return;
        }
        restoreFromCPoolWhenReclassified(organizationId, customerId, actorMembershipId);
        if (normalized.startsWith("A")) {
            ensureSecondLineRecruitment(organizationId, customerId);
        } else {
            closeSecondLineAssignments(organizationId, customerId, "RECLASSIFIED");
        }
    }

    private void restoreFromCPoolWhenReclassified(UUID organizationId, UUID customerId,
                                                   UUID actorMembershipId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select state.status, entry.pool_type, customer.owner_membership_id
                from customer_ownership_states state
                join customers customer on customer.id=state.customer_id
                  and customer.organization_id=state.organization_id
                left join customer_pool_entries entry on entry.id=state.active_pool_entry_id
                where state.organization_id=:organizationId and state.customer_id=:customerId
                for update of state, customer
                """, params("organizationId", organizationId, "customerId", customerId));
        if (rows.isEmpty() || !"C".equals(rows.getFirst().get("pool_type"))) return;
        UUID owner = (UUID) rows.getFirst().get("owner_membership_id");
        beginOwnershipCycle(organizationId, customerId, owner, owner, actorMembershipId,
                "RECLASSIFIED_FROM_C_POOL");
    }

    @Transactional(readOnly = true)
    public List<CustomerPoolDtos.SecondLineRecruitment> openSecondLineRecruitments() {
        CurrentActor actor = requireWritableActor();
        return jdbc.query("""
                select recruitment.id, recruitment.customer_id, customer.full_name customer_name,
                       customer.school, customer.grade,
                       customer.demand_summary, recruitment.required_count, recruitment.claimed_count,
                       recruitment.volunteer_until, recruitment.status
                from second_line_recruitments recruitment
                join customers customer on customer.id=recruitment.customer_id
                  and customer.organization_id=recruitment.organization_id and customer.archived_at is null
                where recruitment.organization_id=:organizationId and recruitment.status='VOLUNTEER_OPEN'
                  and recruitment.volunteer_until>now()
                  and customer.owner_membership_id<>:membershipId
                  and not exists(select 1 from customer_role_assignments assignment
                    where assignment.organization_id=recruitment.organization_id
                      and assignment.customer_id=recruitment.customer_id
                      and assignment.membership_id=:membershipId
                      and assignment.role_type='A_SECOND_LINE' and assignment.status='ACTIVE')
                order by recruitment.opened_at
                """, params("organizationId", actor.organizationId(), "membershipId", actor.membershipId()),
                (row, index) -> new CustomerPoolDtos.SecondLineRecruitment(
                        row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class),
                        row.getString("customer_name"), row.getString("school"), row.getString("grade"),
                        row.getString("demand_summary"),
                        row.getInt("required_count"), row.getInt("claimed_count"),
                        instant(row.getTimestamp("volunteer_until")), row.getString("status")));
    }

    @Transactional
    public CustomerPoolDtos.SecondLineClaimResult claimSecondLine(UUID recruitmentId) {
        CurrentActor actor = requireWritableActor();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select recruitment.*, customer.owner_membership_id
                from second_line_recruitments recruitment
                join customers customer on customer.id=recruitment.customer_id
                  and customer.organization_id=recruitment.organization_id and customer.archived_at is null
                where recruitment.id=:id and recruitment.organization_id=:organizationId
                for update of recruitment, customer
                """, params("id", recruitmentId, "organizationId", actor.organizationId()));
        if (rows.isEmpty() || !"VOLUNTEER_OPEN".equals(rows.getFirst().get("status"))
                || !instant((Timestamp) rows.getFirst().get("volunteer_until")).isAfter(databaseNow())) {
            throw ApiException.conflict("SECOND_LINE_RECRUITMENT_CLOSED", "二线招募已结束");
        }
        Map<String, Object> row = rows.getFirst();
        UUID customerId = (UUID) row.get("customer_id");
        if (actor.membershipId().equals(row.get("owner_membership_id"))) {
            throw ApiException.badRequest("OWNER_CANNOT_BE_SECOND_LINE", "主负责人不能领取自己的二线岗位");
        }
        int activeCount = activeSecondLineCount(actor.organizationId(), customerId);
        if (activeCount >= 2) throw ApiException.conflict("SECOND_LINE_LIMIT_REACHED", "该客户已有两名二线");
        try {
            jdbc.update("""
                    insert into customer_role_assignments
                      (id, organization_id, customer_id, membership_id, role_type, status,
                       assigned_by_membership_id, assignment_source)
                    values (:id, :organizationId, :customerId, :membershipId, 'A_SECOND_LINE', 'ACTIVE',
                       :membershipId, 'VOLUNTEER_CLAIM')
                    """, params("id", UUID.randomUUID(), "organizationId", actor.organizationId(),
                    "customerId", customerId, "membershipId", actor.membershipId()));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("SECOND_LINE_ALREADY_ASSIGNED", "你已经是该客户的二线");
        }
        upsertGrant(actor.organizationId(), customerId, actor.membershipId(), true, actor.membershipId());
        activeCount++;
        int required = ((Number) row.get("required_count")).intValue();
        int claimed = ((Number) row.get("claimed_count")).intValue() + 1;
        boolean closed = claimed >= required || activeCount >= 2;
        jdbc.update("""
                update second_line_recruitments set claimed_count=least(required_count,:claimed),
                  status=case when :closed then 'CLOSED' else status end,
                  closed_at=case when :closed then now() else null end,
                  updated_at=now(), version=version+1 where id=:id
                """, params("claimed", claimed, "closed", closed, "id", recruitmentId));
        audit.record(actor, "A_SECOND_LINE_CLAIMED", "CUSTOMER", customerId.toString(), "SUCCESS",
                Map.of("recruitmentId", recruitmentId.toString()));
        return new CustomerPoolDtos.SecondLineClaimResult(recruitmentId, customerId, activeCount, closed);
    }

    @Transactional
    public void assignSecondLine(UUID customerId, UUID membershipId) {
        CurrentActor actor = requireAdmin();
        lockCustomer(actor.organizationId(), customerId);
        if (!isAClass(actor.organizationId(), customerId)) {
            throw ApiException.badRequest("SECOND_LINE_A_CLASS_REQUIRED", "只有A类客户可以设置A类二线");
        }
        requireEligibleMember(actor.organizationId(), membershipId);
        if (activeSecondLineCount(actor.organizationId(), customerId) >= 2) {
            throw ApiException.conflict("SECOND_LINE_LIMIT_REACHED", "A类客户最多两名二线");
        }
        jdbc.update("""
                insert into customer_role_assignments
                  (id, organization_id, customer_id, membership_id, role_type, status,
                   assigned_by_membership_id, assignment_source)
                values (:id,:organizationId,:customerId,:membershipId,'A_SECOND_LINE','ACTIVE',:actor,'ADMIN_ASSIGN')
                on conflict (organization_id, customer_id, membership_id, role_type)
                  where status='ACTIVE' do nothing
                """, params("id", UUID.randomUUID(), "organizationId", actor.organizationId(),
                "customerId", customerId, "membershipId", membershipId, "actor", actor.membershipId()));
        upsertGrant(actor.organizationId(), customerId, membershipId, true, actor.membershipId());
        closeSatisfiedSecondLineRecruitment(actor.organizationId(), customerId);
        audit.record(actor, "A_SECOND_LINE_ASSIGNED", "CUSTOMER", customerId.toString(), "SUCCESS",
                Map.of("membershipId", membershipId.toString()));
    }

    @Transactional
    public void removeSecondLine(UUID customerId, UUID membershipId) {
        CurrentActor actor = requireAdmin();
        int changed = jdbc.update("""
                update customer_role_assignments set status='ENDED', ended_at=now(), version=version+1
                where organization_id=:organizationId and customer_id=:customerId and membership_id=:membershipId
                  and role_type='A_SECOND_LINE' and status='ACTIVE'
                """, params("organizationId", actor.organizationId(), "customerId", customerId,
                "membershipId", membershipId));
        if (changed == 0) throw ApiException.notFound("SECOND_LINE_NOT_FOUND", "该成员不是当前A类二线");
        Integer otherEditableRole = jdbc.queryForObject("""
                select count(*) from customer_role_assignments where organization_id=:organizationId
                  and customer_id=:customerId and membership_id=:membershipId and status='ACTIVE'
                  and role_type in ('COLLABORATOR','DEAL_ASSISTANT')
                """, params("organizationId", actor.organizationId(), "customerId", customerId,
                "membershipId", membershipId), Integer.class);
        if (otherEditableRole == null || otherEditableRole == 0) {
            jdbc.update("""
                    delete from customer_access_grants where organization_id=:organizationId
                      and customer_id=:customerId and grantee_membership_id=:membershipId
                    """, params("organizationId", actor.organizationId(), "customerId", customerId,
                    "membershipId", membershipId));
        }
        if (isAClass(actor.organizationId(), customerId)) {
            ensureSecondLineRecruitment(actor.organizationId(), customerId);
        }
        audit.record(actor, "A_SECOND_LINE_REMOVED", "CUSTOMER", customerId.toString(), "SUCCESS",
                Map.of("membershipId", membershipId.toString()));
    }

    @Transactional
    public void requestTakeover(UUID organizationId, UUID customerId, String reason, UUID actorMembershipId) {
        Map<String, Object> customer = lockCustomer(organizationId, customerId);
        Integer alreadyPending = jdbc.queryForObject("""
                select count(*) from customer_takeover_tasks where organization_id=:organizationId
                  and customer_id=:customerId and status='ADMIN_PENDING'
                """, params("organizationId", organizationId, "customerId", customerId), Integer.class);
        if (alreadyPending != null && alreadyPending > 0) return;
        List<UUID> contributors = jdbc.queryForList("""
                select assignment.membership_id
                from customer_role_assignments assignment
                join memberships membership on membership.id=assignment.membership_id and membership.status='ACTIVE'
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                where assignment.organization_id=:organizationId and assignment.customer_id=:customerId
                  and assignment.role_type='A_SECOND_LINE' and assignment.status='ACTIVE'
                  and exists(select 1 from interactions interaction
                    where interaction.organization_id=assignment.organization_id
                      and interaction.customer_id=assignment.customer_id
                      and interaction.created_by_membership_id=assignment.membership_id
                      and interaction.created_at>=assignment.started_at
                      and interaction.interaction_type in ('CUSTOMER_REPLY','OUTBOUND_NO_REPLY')
                      and interaction.deadline_effect='APPLIED')
                order by assignment.started_at
                """, params("organizationId", organizationId, "customerId", customerId), UUID.class);
        if (contributors.isEmpty()) {
            releaseLocked(organizationId, customer, PoolType.AB, ReleaseReason.OWNER_INACTIVE,
                    actorMembershipId, reason);
            return;
        }
        UUID taskId = UUID.randomUUID();
        jdbc.update("""
                insert into customer_takeover_tasks
                  (id, organization_id, customer_id, previous_owner_membership_id, reason, status)
                values (:id,:organizationId,:customerId,:previousOwner,:reason,'ADMIN_PENDING')
                """, params("id", taskId, "organizationId", organizationId, "customerId", customerId,
                "previousOwner", customer.get("owner_membership_id"), "reason", reason));
        jdbc.update("""
                insert into customer_ownership_states
                  (customer_id, organization_id, status, previous_owner_membership_id)
                values (:customerId,:organizationId,'FROZEN_TAKEOVER',:previousOwner)
                on conflict (customer_id) do update set status='FROZEN_TAKEOVER',
                  previous_owner_membership_id=:previousOwner, updated_at=now(), version=customer_ownership_states.version+1
                """, params("customerId", customerId, "organizationId", organizationId,
                "previousOwner", customer.get("owner_membership_id")));
        notifications.publish(organizationId, adminRecipients(organizationId), "SECOND_LINE_TAKEOVER",
                "A类客户接任待处理", "A类客户已有有效二线推进，需管理员确认接任人。",
                "/admin/governance", "second-line-takeover:" + taskId);
        ownershipEvent(organizationId, customerId, "SECOND_LINE_TAKEOVER_REQUIRED", actorMembershipId,
                "ASSIGNED", "FROZEN_TAKEOVER", null);
    }

    @Transactional
    public boolean handleDeadlineExpiry(UUID organizationId, UUID customerId) {
        Integer contributors = jdbc.queryForObject("""
                select count(*) from customer_role_assignments assignment
                join memberships membership on membership.id=assignment.membership_id and membership.status='ACTIVE'
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                where assignment.organization_id=:organizationId and assignment.customer_id=:customerId
                  and assignment.role_type='A_SECOND_LINE' and assignment.status='ACTIVE'
                  and exists(select 1 from interactions interaction
                    where interaction.organization_id=assignment.organization_id
                      and interaction.customer_id=assignment.customer_id
                      and interaction.created_by_membership_id=assignment.membership_id
                      and interaction.created_at>=assignment.started_at
                      and interaction.interaction_type in ('CUSTOMER_REPLY','OUTBOUND_NO_REPLY')
                      and interaction.deadline_effect='APPLIED')
                """, params("organizationId", organizationId, "customerId", customerId), Integer.class);
        if (isAClass(organizationId, customerId) && contributors != null && contributors > 0) {
            requestTakeover(organizationId, customerId, "DEADLINE_EXPIRED_WITH_ACTIVE_SECOND_LINE", null);
            return false;
        }
        releaseToPool(organizationId, customerId, PoolType.AB, ReleaseReason.DEADLINE_EXPIRED, null);
        return true;
    }

    @Transactional
    public void resolveTakeover(UUID taskId, CustomerPoolDtos.ResolveTakeoverRequest request) {
        CurrentActor actor = requireAdmin();
        List<Map<String, Object>> tasks = jdbc.queryForList("""
                select * from customer_takeover_tasks where id=:id and organization_id=:organizationId
                  and status='ADMIN_PENDING' for update
                """, params("id", taskId, "organizationId", actor.organizationId()));
        if (tasks.isEmpty()) throw ApiException.conflict("TAKEOVER_TASK_CLOSED", "接任任务不存在或已处理");
        Map<String, Object> task = tasks.getFirst();
        UUID customerId = (UUID) task.get("customer_id");
        requireActiveSecondLine(actor.organizationId(), customerId, request.selectedMembershipId());
        transferOwnership(actor.organizationId(), customerId,
                (UUID) task.get("previous_owner_membership_id"), request.selectedMembershipId(),
                actor.membershipId(), "SECOND_LINE_ADMIN_TAKEOVER");
        jdbc.update("""
                update customer_takeover_tasks set status='RESOLVED', selected_membership_id=:selected,
                  resolved_at=now(), resolved_by_membership_id=:actor, resolution_reason=:reason
                where id=:id
                """, params("selected", request.selectedMembershipId(), "actor", actor.membershipId(),
                "reason", request.reason().trim(), "id", taskId));
        audit.record(actor, "SECOND_LINE_TAKEOVER_RESOLVED", "CUSTOMER", customerId.toString(), "SUCCESS",
                Map.of("selectedMembershipId", request.selectedMembershipId().toString(),
                        "reason", request.reason().trim()));
    }

    @Transactional(readOnly = true)
    public List<CustomerPoolDtos.SecondLineAssignment> secondLines(UUID customerId) {
        CurrentActor actor = actors.require();
        requireCustomerVisible(actor, customerId);
        return jdbc.query("""
                select assignment.membership_id, account.display_name, assignment.started_at,
                       assignment.assignment_source
                from customer_role_assignments assignment
                join memberships membership on membership.id=assignment.membership_id
                join user_accounts account on account.id=membership.user_id
                where assignment.organization_id=:organizationId and assignment.customer_id=:customerId
                  and assignment.role_type='A_SECOND_LINE' and assignment.status='ACTIVE'
                order by assignment.started_at
                """, params("organizationId", actor.organizationId(), "customerId", customerId),
                (row, index) -> new CustomerPoolDtos.SecondLineAssignment(
                        row.getObject("membership_id", UUID.class), row.getString("display_name"),
                        instant(row.getTimestamp("started_at")), row.getString("assignment_source")));
    }

    @Transactional
    public CustomerPoolDtos.ContactAuthorization requestContactAuthorization(
            UUID customerId, CustomerPoolDtos.ContactAuthorizationRequest request) {
        CurrentActor actor = requireWritableActor();
        requireActiveSecondLine(actor.organizationId(), customerId, actor.membershipId());
        Map<String, Object> customer = lockCustomer(actor.organizationId(), customerId);
        UUID owner = (UUID) customer.get("owner_membership_id");
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into second_line_contact_authorizations
                      (id, organization_id, customer_id, requester_membership_id, owner_membership_id,
                       purpose, status, owner_deadline)
                    values (:id,:organizationId,:customerId,:requester,:owner,:purpose,
                       'PENDING_OWNER',now()+interval '24 hours')
                    """, params("id", id, "organizationId", actor.organizationId(), "customerId", customerId,
                    "requester", actor.membershipId(), "owner", owner, "purpose", request.purpose().trim()));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("CONTACT_AUTHORIZATION_PENDING", "已有待处理的联系授权申请");
        }
        audit.record(actor, "SECOND_LINE_CONTACT_AUTH_REQUESTED", "CUSTOMER", customerId.toString(), "SUCCESS",
                Map.of("authorizationId", id.toString()));
        notifications.publish(actor.organizationId(), List.of(owner), "SECOND_LINE_CONTACT_AUTH",
                "二线联系授权待处理", "一项客户联系授权申请等待处理。",
                "/customers/" + customerId, "second-line-contact-auth:" + id);
        return contactAuthorization(actor.organizationId(), id);
    }

    @Transactional
    public CustomerPoolDtos.ContactAuthorization decideContactAuthorization(
            UUID authorizationId, CustomerPoolDtos.ContactAuthorizationDecision decision) {
        CurrentActor actor = actors.require();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from second_line_contact_authorizations
                where id=:id and organization_id=:organizationId for update
                """, params("id", authorizationId, "organizationId", actor.organizationId()));
        if (rows.isEmpty()) throw ApiException.notFound("CONTACT_AUTHORIZATION_NOT_FOUND", "联系授权申请不存在");
        Map<String, Object> row = rows.getFirst();
        String status = (String) row.get("status");
        boolean ownerDecision = actor.membershipId().equals(row.get("owner_membership_id"))
                && "PENDING_OWNER".equals(status);
        boolean adminDecision = actor.isAdmin() && Set.of("PENDING_OWNER", "ADMIN_PENDING").contains(status);
        if (!ownerDecision && !adminDecision) {
            throw ApiException.forbidden("当前账号不能处理该联系授权申请");
        }
        long version = ((Number) row.get("version")).longValue();
        if (decision.expectedVersion() != null && decision.expectedVersion() != version) {
            throw ApiException.conflict("STALE_VERSION", "授权申请已被处理，请刷新");
        }
        String next = Boolean.TRUE.equals(decision.approved()) ? "APPROVED" : "DENIED";
        jdbc.update("""
                update second_line_contact_authorizations set status=:status,
                  decided_by_membership_id=:actor, decision_reason=:reason, decided_at=now(), version=version+1
                where id=:id
                """, params("status", next, "actor", actor.membershipId(),
                "reason", TextNormalizer.optional(decision.reason()), "id", authorizationId));
        audit.record(actor, "SECOND_LINE_CONTACT_AUTH_DECIDED", "CUSTOMER", row.get("customer_id").toString(),
                "SUCCESS", Map.of("authorizationId", authorizationId.toString(), "status", next));
        notifications.publish(actor.organizationId(), List.of((UUID) row.get("requester_membership_id")),
                "SECOND_LINE_CONTACT_AUTH", "二线联系授权已处理",
                "一项客户联系授权申请已处理。", "/customers/" + row.get("customer_id"),
                "second-line-contact-auth-decision:" + authorizationId + ":" + next);
        return contactAuthorization(actor.organizationId(), authorizationId);
    }

    @Transactional(readOnly = true)
    public void requireSecondLineContactAuthorized(UUID customerId, String interactionType) {
        if (!OWNERSHIP_ACTIONS.contains(interactionType)) return;
        CurrentActor actor = actors.require();
        Integer secondLine = jdbc.queryForObject("""
                select count(*) from customer_role_assignments where organization_id=:organizationId
                  and customer_id=:customerId and membership_id=:membershipId
                  and role_type='A_SECOND_LINE' and status='ACTIVE'
                """, params("organizationId", actor.organizationId(), "customerId", customerId,
                "membershipId", actor.membershipId()), Integer.class);
        if (secondLine == null || secondLine == 0) return;
        Integer authorized = jdbc.queryForObject("""
                select count(*) from second_line_contact_authorizations where organization_id=:organizationId
                  and customer_id=:customerId and requester_membership_id=:membershipId and status='APPROVED'
                """, params("organizationId", actor.organizationId(), "customerId", customerId,
                "membershipId", actor.membershipId()), Integer.class);
        if (authorized == null || authorized == 0) {
            throw ApiException.conflict("SECOND_LINE_CONTACT_AUTH_REQUIRED", "A类二线直接联系客户前必须取得授权");
        }
    }

    @Transactional
    public int releaseMemberCustomers(UUID organizationId, UUID membershipId, UUID actorMembershipId) {
        boolean cyclesExist = Boolean.TRUE.equals(jdbc.queryForObject(
                "select to_regclass('public.customer_business_cycles') is not null", Map.of(), Boolean.class));
        String unsettled = cyclesExist ? """
                and (not exists(select 1 from customer_business_cycles cycle
                       where cycle.organization_id=customer.organization_id and cycle.customer_id=customer.id)
                     or coalesce((select cycle.status from customer_business_cycles cycle
                         where cycle.organization_id=customer.organization_id and cycle.customer_id=customer.id
                         order by cycle.cycle_number desc limit 1),'IN_PROGRESS')<>'WON')
                """ : "";
        List<UUID> customerIds = jdbc.queryForList("""
                select customer.id from customers customer
                where customer.organization_id=:organizationId and customer.owner_membership_id=:membershipId
                  and customer.archived_at is null
                """ + unsettled + " for update", params("organizationId", organizationId,
                "membershipId", membershipId), UUID.class);
        for (UUID customerId : customerIds) {
            releaseToPool(organizationId, customerId, qualityPoolType(organizationId, customerId),
                    ReleaseReason.MEMBER_DEPARTURE, actorMembershipId);
        }
        jdbc.update("""
                update customer_role_assignments set status='ENDED', ended_at=now(), version=version+1
                where organization_id=:organizationId and membership_id=:membershipId and status='ACTIVE'
                """, params("organizationId", organizationId, "membershipId", membershipId));
        if (!customerIds.isEmpty()) {
            notifications.publish(organizationId, adminRecipients(organizationId), "MEMBER_DEPARTURE",
                    "成员离队客户已进入公共池", "离队成员的未成交客户已按等级进入对应客户池。",
                    "/admin/governance", "member-departure:" + membershipId + ":" + customerIds.size());
        }
        return customerIds.size();
    }

    @Scheduled(cron = "0 */10 * * * *", zone = "Asia/Shanghai")
    @Transactional
    public void expireTemporaryClaimsAndEscalate() {
        List<Map<String, Object>> expired = jdbc.queryForList("""
                select claim.id, claim.organization_id, claim.customer_id, claim.pool_entry_id,
                       claim.claimant_membership_id, entry.pool_type
                from customer_claim_records claim
                join customer_pool_entries entry on entry.id=claim.pool_entry_id
                where claim.status='TEMPORARY' and claim.expires_at<=now()
                for update of claim, entry skip locked
                """, Map.of());
        for (Map<String, Object> claim : expired) expireClaim(claim);
        List<Map<String, Object>> recruitments = jdbc.queryForList("""
                select id, organization_id, customer_id from second_line_recruitments
                where status='VOLUNTEER_OPEN' and volunteer_until<=now()
                for update skip locked
                """, Map.of());
        for (Map<String, Object> recruitment : recruitments) {
            jdbc.update("""
                    update second_line_recruitments set status='ADMIN_PENDING', updated_at=now(), version=version+1
                    where id=:id
                    """, params("id", recruitment.get("id")));
            UUID organizationId = (UUID) recruitment.get("organization_id");
            notifications.publish(organizationId, adminRecipients(organizationId), "SECOND_LINE_REQUIRED",
                    "A类二线招募需管理员指定", "24小时自愿领取期已结束，需管理员指定二线。",
                    "/admin/governance", "second-line-admin-pending:" + recruitment.get("id"));
        }
        List<Map<String, Object>> authorizations = jdbc.queryForList("""
                select id, organization_id, customer_id, requester_membership_id
                from second_line_contact_authorizations
                where status='PENDING_OWNER' and owner_deadline<=now()
                for update skip locked
                """, Map.of());
        for (Map<String, Object> authorization : authorizations) {
            jdbc.update("""
                    update second_line_contact_authorizations set status='ADMIN_PENDING', version=version+1
                    where id=:id
                    """, params("id", authorization.get("id")));
            UUID organizationId = (UUID) authorization.get("organization_id");
            notifications.publish(organizationId, recipients(organizationId,
                            (UUID) authorization.get("requester_membership_id")),
                    "SECOND_LINE_CONTACT_AUTH", "二线联系授权已转管理员",
                    "主负责人24小时内未处理，一项联系授权已转管理员。",
                    "/admin/governance", "contact-auth-admin-pending:" + authorization.get("id"));
        }
        List<Map<String, Object>> inactiveOwners = jdbc.queryForList("""
                select customer.organization_id, customer.id
                from customers customer
                join memberships owner on owner.id=customer.owner_membership_id
                join user_accounts account on account.id=owner.user_id
                where customer.archived_at is null and (owner.status<>'ACTIVE' or account.enabled=false)
                  and not exists(select 1 from customer_ownership_states state
                    where state.customer_id=customer.id and state.status<>'ASSIGNED')
                  and exists(select 1 from customer_tags customer_tag join tags tag on tag.id=customer_tag.tag_id
                    where customer_tag.customer_id=customer.id and tag.category='客户质量'
                      and tag.name='A类用户' and tag.active=true)
                for update of customer skip locked
                """, Map.of());
        for (Map<String, Object> customer : inactiveOwners) {
            requestTakeover((UUID) customer.get("organization_id"), (UUID) customer.get("id"),
                    "OWNER_INACTIVE", null);
        }
    }

    private UUID releaseLocked(UUID organizationId, Map<String, Object> customer, PoolType type,
                               ReleaseReason reason, UUID actorMembershipId, String note) {
        UUID customerId = (UUID) customer.get("id");
        if (customer.get("archived_at") != null) {
            throw ApiException.conflict("ARCHIVED_CUSTOMER_POOL_FORBIDDEN", "归档客户不能进入公共池");
        }
        List<UUID> active = jdbc.queryForList("""
                select id from customer_pool_entries where organization_id=:organizationId
                  and customer_id=:customerId and status in ('OPEN','TEMP_CLAIMED') for update
                """, params("organizationId", organizationId, "customerId", customerId), UUID.class);
        if (!active.isEmpty()) return active.getFirst();
        UUID previousOwner = (UUID) customer.get("owner_membership_id");
        UUID priorityOwner = type == PoolType.C ? (UUID) customer.get("created_by_membership_id") : null;
        Instant priorityUntil = priorityOwner == null ? null : Instant.now().plusSeconds(24 * 60 * 60);
        UUID entryId = UUID.randomUUID();
        jdbc.update("""
                insert into customer_pool_entries
                  (id, organization_id, customer_id, pool_type, release_reason, release_note,
                   previous_owner_membership_id, priority_owner_membership_id, priority_until)
                values (:id,:organizationId,:customerId,:poolType,:reason,:note,:previousOwner,
                   :priorityOwner,:priorityUntil)
                """, params("id", entryId, "organizationId", organizationId, "customerId", customerId,
                "poolType", type.name(), "reason", reason.name(), "note", note,
                "previousOwner", previousOwner, "priorityOwner", priorityOwner,
                "priorityUntil", priorityUntil == null ? null : Timestamp.from(priorityUntil)));
        jdbc.update("""
                insert into customer_ownership_states
                  (customer_id, organization_id, status, active_pool_entry_id, previous_owner_membership_id,
                   ownership_cycle)
                values (:customerId,:organizationId,:status,:entryId,:previousOwner,1)
                on conflict (customer_id) do update set status=:status, active_pool_entry_id=:entryId,
                  previous_owner_membership_id=:previousOwner, temporary_claim_membership_id=null,
                  temporary_claim_expires_at=null, ownership_cycle=customer_ownership_states.ownership_cycle+1,
                  updated_at=now(), version=customer_ownership_states.version+1
                """, params("customerId", customerId, "organizationId", organizationId,
                "status", type == PoolType.C ? "POOLED_C" : "POOLED_AB", "entryId", entryId,
                "previousOwner", previousOwner));
        jdbc.update("""
                delete from customer_access_grants where organization_id=:organizationId
                  and customer_id=:customerId and grantee_membership_id is not null
                """, params("organizationId", organizationId, "customerId", customerId));
        closeSecondLineAssignments(organizationId, customerId, "CUSTOMER_POOLED");
        jdbc.update("""
                update customer_role_assignments set status='ENDED', ended_at=now(), version=version+1
                where organization_id=:organizationId and customer_id=:customerId
                  and role_type='COLLABORATOR' and status='ACTIVE'
                """, params("organizationId", organizationId, "customerId", customerId));
        jdbc.update("""
                update follow_up_recruitments set status='CANCELLED', closed_at=now(), updated_at=now(), version=version+1
                where organization_id=:organizationId and customer_id=:customerId and status='OPEN'
                """, params("organizationId", organizationId, "customerId", customerId));
        ownershipEvent(organizationId, customerId, "CUSTOMER_RELEASED", actorMembershipId, "ASSIGNED",
                type == PoolType.C ? "POOLED_C" : "POOLED_AB", entryId);
        notifications.publish(organizationId, recipients(organizationId, previousOwner),
                "CUSTOMER_POOL_RELEASED", "客户已进入公共池",
                type == PoolType.C ? "一名脱敏客户已进入C类沉淀池。" : "一名脱敏客户已进入A/B公共池。",
                "/customer-pools", "customer-pool-release:" + entryId);
        return entryId;
    }

    private void ensureSecondLineRecruitment(UUID organizationId, UUID customerId) {
        int count = activeSecondLineCount(organizationId, customerId);
        if (count >= 1) {
            closeSatisfiedSecondLineRecruitment(organizationId, customerId);
            return;
        }
        jdbc.update("""
                insert into second_line_recruitments
                  (id, organization_id, customer_id, required_count, claimed_count,
                   status, volunteer_until)
                values (:id,:organizationId,:customerId,1,0,'VOLUNTEER_OPEN',now()+interval '24 hours')
                on conflict (organization_id, customer_id)
                  where status in ('VOLUNTEER_OPEN','ADMIN_PENDING') do nothing
                """, params("id", UUID.randomUUID(), "organizationId", organizationId, "customerId", customerId));
        notifications.publish(organizationId, adminRecipients(organizationId), "SECOND_LINE_REQUIRED",
                "A类客户缺少二线", "一名A类客户已开放24小时二线招募。",
                "/second-line-recruitments", "second-line-required:" + customerId);
    }

    private void closeSatisfiedSecondLineRecruitment(UUID organizationId, UUID customerId) {
        if (activeSecondLineCount(organizationId, customerId) < 1) return;
        jdbc.update("""
                update second_line_recruitments set status='CLOSED', closed_at=now(), updated_at=now(), version=version+1
                where organization_id=:organizationId and customer_id=:customerId
                  and status in ('VOLUNTEER_OPEN','ADMIN_PENDING')
                """, params("organizationId", organizationId, "customerId", customerId));
    }

    private int activeSecondLineCount(UUID organizationId, UUID customerId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from customer_role_assignments where organization_id=:organizationId
                  and customer_id=:customerId and role_type='A_SECOND_LINE' and status='ACTIVE'
                """, params("organizationId", organizationId, "customerId", customerId), Integer.class);
        return count == null ? 0 : count;
    }

    private void closeSecondLineAssignments(UUID organizationId, UUID customerId, String source) {
        jdbc.update("""
                delete from customer_access_grants grant_row
                where grant_row.organization_id=:organizationId and grant_row.customer_id=:customerId
                  and grant_row.grantee_membership_id is not null
                  and exists(select 1 from customer_role_assignments assignment
                    where assignment.organization_id=:organizationId and assignment.customer_id=:customerId
                      and assignment.membership_id=grant_row.grantee_membership_id
                      and assignment.role_type='A_SECOND_LINE' and assignment.status='ACTIVE')
                  and not exists(select 1 from customer_role_assignments retained
                    where retained.organization_id=:organizationId and retained.customer_id=:customerId
                      and retained.membership_id=grant_row.grantee_membership_id
                      and retained.role_type in ('COLLABORATOR','DEAL_ASSISTANT') and retained.status='ACTIVE')
                """, params("organizationId", organizationId, "customerId", customerId));
        jdbc.update("""
                update customer_role_assignments set status='ENDED', ended_at=now(), version=version+1
                where organization_id=:organizationId and customer_id=:customerId
                  and role_type='A_SECOND_LINE' and status='ACTIVE'
                """, params("organizationId", organizationId, "customerId", customerId));
        jdbc.update("""
                update second_line_recruitments set status='CANCELLED', closed_at=now(), updated_at=now(), version=version+1
                where organization_id=:organizationId and customer_id=:customerId
                  and status in ('VOLUNTEER_OPEN','ADMIN_PENDING')
                """, params("organizationId", organizationId, "customerId", customerId));
    }

    private void expireClaim(Map<String, Object> claim) {
        UUID organizationId = (UUID) claim.get("organization_id");
        UUID customerId = (UUID) claim.get("customer_id");
        UUID membershipId = (UUID) claim.get("claimant_membership_id");
        jdbc.update("update customer_claim_records set status='EXPIRED', ended_at=now() where id=:id",
                params("id", claim.get("id")));
        jdbc.update("""
                update customer_pool_entries set status='OPEN', release_reason='CLAIM_EXPIRED',
                  temporary_claim_membership_id=null, temporary_claim_expires_at=null,
                  updated_at=now(), version=version+1 where id=:entryId
                """, params("entryId", claim.get("pool_entry_id")));
        jdbc.update("""
                update customer_ownership_states set status=:status,
                  temporary_claim_membership_id=null, temporary_claim_expires_at=null,
                  updated_at=now(), version=version+1 where organization_id=:organizationId and customer_id=:customerId
                """, params("status", "C".equals(claim.get("pool_type")) ? "POOLED_C" : "POOLED_AB",
                "organizationId", organizationId, "customerId", customerId));
        jdbc.update("""
                delete from customer_access_grants where organization_id=:organizationId
                  and customer_id=:customerId and grantee_membership_id=:membershipId
                """, params("organizationId", organizationId, "customerId", customerId,
                "membershipId", membershipId));
        jdbc.update("""
                insert into customer_claim_cooldowns
                  (organization_id, customer_id, membership_id, cooldown_until, reason)
                values (:organizationId,:customerId,:membershipId,now()+interval '48 hours','TEMPORARY_CLAIM_EXPIRED')
                on conflict (organization_id, customer_id, membership_id) do update
                  set cooldown_until=excluded.cooldown_until, reason=excluded.reason, created_at=now()
                """, params("organizationId", organizationId, "customerId", customerId,
                "membershipId", membershipId));
        ownershipEvent(organizationId, customerId, "TEMPORARY_CLAIM_EXPIRED", membershipId,
                "TEMP_CLAIMED", "C".equals(claim.get("pool_type")) ? "POOLED_C" : "POOLED_AB",
                (UUID) claim.get("pool_entry_id"));
        notifications.publish(organizationId, recipients(organizationId, membershipId),
                "CUSTOMER_RETURNED_TO_POOL", "临时认领已失效",
                "一名脱敏客户因24小时内未完成首次有效行动而返回客户池。",
                "/customer-pools", "pool-return:" + claim.get("id"));
    }

    private void transferOwnership(UUID organizationId, UUID customerId, UUID previousOwner,
                                   UUID newOwner, UUID actorMembershipId, String source) {
        UUID teamId = firstActiveTeam(newOwner);
        jdbc.update("""
                update customers set owner_membership_id=:newOwner, team_id=:teamId,
                  updated_at=now(), version=version+1
                where organization_id=:organizationId and id=:customerId and archived_at is null
                """, params("newOwner", newOwner, "teamId", teamId,
                "organizationId", organizationId, "customerId", customerId));
        if (previousOwner != null && !previousOwner.equals(newOwner)) {
            upsertGrant(organizationId, customerId, previousOwner, false,
                    actorMembershipId == null ? newOwner : actorMembershipId);
            jdbc.update("""
                    update reminders set assigned_membership_id=:newOwner, updated_at=now(), version=version+1
                    where organization_id=:organizationId and customer_id=:customerId
                      and assigned_membership_id=:previousOwner and status='PENDING'
                    """, params("newOwner", newOwner, "organizationId", organizationId,
                    "customerId", customerId, "previousOwner", previousOwner));
        }
        jdbc.update("""
                update customer_role_assignments set status='ENDED', ended_at=now(), version=version+1
                where organization_id=:organizationId and customer_id=:customerId
                  and membership_id=:newOwner and role_type='A_SECOND_LINE' and status='ACTIVE'
                """, params("organizationId", organizationId, "customerId", customerId, "newOwner", newOwner));
        jdbc.update("""
                insert into customer_ownership_states
                  (customer_id, organization_id, status, previous_owner_membership_id, ownership_cycle)
                values (:customerId,:organizationId,'ASSIGNED',:previousOwner,1)
                on conflict (customer_id) do update set status='ASSIGNED', active_pool_entry_id=null,
                  previous_owner_membership_id=:previousOwner, temporary_claim_membership_id=null,
                  temporary_claim_expires_at=null, ownership_cycle=customer_ownership_states.ownership_cycle+1,
                  updated_at=now(), version=customer_ownership_states.version+1
                """, params("customerId", customerId, "organizationId", organizationId,
                "previousOwner", previousOwner));
        ownershipEvent(organizationId, customerId, source, actorMembershipId,
                "FROZEN_TAKEOVER", "ASSIGNED", null);
        notifications.publish(organizationId, recipients(organizationId, previousOwner, newOwner),
                "SECOND_LINE_TAKEOVER", "A类客户负责人已接任",
                "一名A类客户已按二线贡献完成负责人接任。", "/customers/" + customerId,
                "second-line-takeover-complete:" + customerId + ":" + newOwner);
    }

    private CustomerPoolDtos.ContactAuthorization contactAuthorization(UUID organizationId, UUID id) {
        return jdbc.query("""
                select * from second_line_contact_authorizations where organization_id=:organizationId and id=:id
                """, params("organizationId", organizationId, "id", id), (row, index) ->
                new CustomerPoolDtos.ContactAuthorization(row.getObject("id", UUID.class),
                        row.getObject("customer_id", UUID.class), row.getObject("requester_membership_id", UUID.class),
                        row.getObject("owner_membership_id", UUID.class), row.getString("purpose"), row.getString("status"),
                        instant(row.getTimestamp("owner_deadline")), row.getObject("decided_by_membership_id", UUID.class),
                        row.getString("decision_reason"), instant(row.getTimestamp("created_at")),
                        instant(row.getTimestamp("decided_at")), row.getLong("version"))).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("CONTACT_AUTHORIZATION_NOT_FOUND", "联系授权申请不存在"));
    }

    private void requireActiveSecondLine(UUID organizationId, UUID customerId, UUID membershipId) {
        if (activeRoleExists(organizationId, customerId, membershipId, "A_SECOND_LINE")) return;
        throw ApiException.forbidden("只有当前A类二线可以申请联系客户");
    }

    private boolean activeRoleExists(UUID organizationId, UUID customerId, UUID membershipId, String roleType) {
        Integer count = jdbc.queryForObject("""
                select count(*) from customer_role_assignments where organization_id=:organizationId
                  and customer_id=:customerId and membership_id=:membershipId
                  and role_type=:roleType and status='ACTIVE'
                """, params("organizationId", organizationId, "customerId", customerId,
                "membershipId", membershipId, "roleType", roleType), Integer.class);
        return count != null && count > 0;
    }

    private void requireCustomerVisible(CurrentActor actor, UUID customerId) {
        Integer visible = jdbc.queryForObject("""
                select count(*) from customers customer where customer.organization_id=:organizationId
                  and customer.id=:customerId and (customer.owner_membership_id=:membershipId
                    or exists(select 1 from customer_access_grants grant_row
                      where grant_row.customer_id=customer.id and grant_row.grantee_membership_id=:membershipId)
                    or :admin=true)
                """, params("organizationId", actor.organizationId(), "customerId", customerId,
                "membershipId", actor.membershipId(), "admin", actor.isAdmin()), Integer.class);
        if (visible == null || visible == 0) throw ApiException.notFound("CUSTOMER_NOT_FOUND", "客户不存在或无权访问");
    }

    private void requireEligibleMember(UUID organizationId, UUID membershipId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from memberships membership join user_accounts account on account.id=membership.user_id
                where membership.organization_id=:organizationId and membership.id=:membershipId
                  and membership.status='ACTIVE' and account.enabled=true
                """, params("organizationId", organizationId, "membershipId", membershipId), Integer.class);
        if (count == null || count == 0) throw ApiException.badRequest("SECOND_LINE_NOT_ELIGIBLE", "二线必须是组织内启用成员");
    }

    private Map<String, Object> lockCustomer(UUID organizationId, UUID customerId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, owner_membership_id, created_by_membership_id, archived_at
                from customers where organization_id=:organizationId and id=:customerId for update
                """, params("organizationId", organizationId, "customerId", customerId));
        if (rows.isEmpty()) throw ApiException.notFound("CUSTOMER_NOT_FOUND", "客户不存在");
        return rows.getFirst();
    }

    private PoolType qualityPoolType(UUID organizationId, UUID customerId) {
        Integer cClass = jdbc.queryForObject("""
                select count(*) from customer_tags customer_tag join tags tag on tag.id=customer_tag.tag_id
                where customer_tag.customer_id=:customerId and tag.organization_id=:organizationId
                  and tag.active=true and tag.category='客户质量' and tag.name='C类用户'
                """, params("customerId", customerId, "organizationId", organizationId), Integer.class);
        return cClass != null && cClass > 0 ? PoolType.C : PoolType.AB;
    }

    private boolean isAClass(UUID organizationId, UUID customerId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from customer_tags customer_tag join tags tag on tag.id=customer_tag.tag_id
                where customer_tag.customer_id=:customerId and tag.organization_id=:organizationId
                  and tag.active=true and tag.category='客户质量' and tag.name='A类用户'
                """, params("customerId", customerId, "organizationId", organizationId), Integer.class);
        return count != null && count > 0;
    }

    private UUID firstActiveTeam(UUID membershipId) {
        return jdbc.query("""
                select team_member.team_id from team_members team_member
                join teams team on team.id=team_member.team_id and team.active=true
                where team_member.membership_id=:membershipId order by team_member.created_at limit 1
                """, params("membershipId", membershipId), (row, index) -> row.getObject("team_id", UUID.class))
                .stream().findFirst().orElse(null);
    }

    private void upsertGrant(UUID organizationId, UUID customerId, UUID membershipId,
                             boolean canEdit, UUID grantedBy) {
        jdbc.update("""
                insert into customer_access_grants
                  (id, organization_id, customer_id, grantee_membership_id, can_edit, granted_by_membership_id)
                values (:id,:organizationId,:customerId,:membershipId,:canEdit,:grantedBy)
                on conflict (organization_id, customer_id, grantee_membership_id)
                  where grantee_membership_id is not null do update set can_edit=excluded.can_edit,
                    granted_by_membership_id=excluded.granted_by_membership_id, created_at=now()
                """, params("id", UUID.randomUUID(), "organizationId", organizationId,
                "customerId", customerId, "membershipId", membershipId,
                "canEdit", canEdit, "grantedBy", grantedBy));
    }

    private void ownershipEvent(UUID organizationId, UUID customerId, String eventType,
                                UUID actorMembershipId, String previousState, String resultingState,
                                UUID poolEntryId) {
        jdbc.update("""
                insert into customer_ownership_events
                  (id, organization_id, customer_id, event_type, actor_membership_id,
                   previous_state, resulting_state, details)
                values (:id,:organizationId,:customerId,:eventType,:actor,:previousState,:resultingState,
                  jsonb_build_object('poolEntryId',cast(:poolEntryId as text)))
                """, params("id", UUID.randomUUID(), "organizationId", organizationId,
                "customerId", customerId, "eventType", eventType, "actor", actorMembershipId,
                "previousState", previousState, "resultingState", resultingState,
                "poolEntryId", poolEntryId == null ? "" : poolEntryId.toString()));
    }

    private List<UUID> adminRecipients(UUID organizationId) {
        return jdbc.queryForList("""
                select distinct membership.id from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                join membership_roles membership_role on membership_role.membership_id=membership.id
                join roles role on role.id=membership_role.role_id and role.code='ORG_ADMIN'
                where membership.organization_id=:organizationId and membership.status='ACTIVE'
                """, params("organizationId", organizationId), UUID.class);
    }

    private List<UUID> recipients(UUID organizationId, UUID... direct) {
        var result = new LinkedHashSet<>(adminRecipients(organizationId));
        for (UUID membershipId : direct) if (membershipId != null) result.add(membershipId);
        return List.copyOf(result);
    }

    private List<CustomerPoolDtos.MaskedTimeline> maskedTimeline(UUID customerId) {
        return jdbc.query("""
                select contacted_at, channel from interactions where customer_id=:customerId
                order by contacted_at desc limit 5
                """, params("customerId", customerId), (row, index) -> new CustomerPoolDtos.MaskedTimeline(
                instant(row.getTimestamp("contacted_at")), row.getString("channel"), "已记录一次沟通"));
    }

    private CustomerPoolDtos.ClaimResult claimResult(java.sql.ResultSet row, boolean replay)
            throws java.sql.SQLException {
        return new CustomerPoolDtos.ClaimResult(row.getObject("id", UUID.class),
                row.getObject("pool_entry_id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getString("status"), instant(row.getTimestamp("claimed_at")),
                instant(row.getTimestamp("expires_at")), replay);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("select now()", Map.of(), Instant.class);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private CurrentActor requireWritableActor() {
        CurrentActor actor = actors.require();
        if (!actor.canWriteCustomers()) throw ApiException.forbidden("当前角色不能认领或管理客户");
        return actor;
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin()) throw ApiException.forbidden("该操作仅限组织管理员");
        return actor;
    }

    private static MapSqlParameterSource params(Object... values) {
        var source = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) {
            source.addValue(String.valueOf(values[index]), values[index + 1]);
        }
        return source;
    }
}
