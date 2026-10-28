package com.yingge.cloud.deal.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.feishu.api.FeishuDealBotDtos;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DealConfirmationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("M.d");
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final CustomerService customers;
    private final AuditService audit;
    private final ObjectMapper json;

    public DealConfirmationService(JdbcTemplate jdbc, CurrentActorProvider actors,
                                   PermissionService permissions, CustomerService customers,
                                   AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.customers = customers;
        this.audit = audit;
        this.json = json;
    }

    @Transactional
    public UUID confirmFromFeishu(FeishuConfirmation command) {
        return confirmFromFeishu(command, "FEISHU_BOT");
    }

    @Transactional
    public UUID confirmFromFeishuWeb(FeishuConfirmation command) {
        return confirmFromFeishu(command, "FEISHU_WEB");
    }

    private UUID confirmFromFeishu(FeishuConfirmation command, String confirmationChannel) {
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        if (command.orderAmountCents() <= 0) {
            throw ApiException.badRequest("DEAL_ORDER_AMOUNT_REQUIRED", "订单金额必须大于 0");
        }
        if (command.totalBonusCents() <= 0) {
            throw ApiException.badRequest("DEAL_BONUS_REQUIRED", "奖金总额必须大于 0");
        }
        var customer = customers.requireVisibleEntity(command.customerId(), actor);
        customers.requireProfileEditable(customer, actor);
        jdbc.queryForObject("select id from customers where id=? and organization_id=? for update",
                UUID.class, command.customerId(), actor.organizationId());
        if (customer.getArchivedAt() != null) {
            throw ApiException.conflict("ARCHIVED_CUSTOMER_DEAL_FORBIDDEN", "归档客户不能确认成单");
        }

        LinkedHashMap<UUID, Long> allocations = validateAttribution(actor, command.primaryMembershipId(),
                command.assistantMembershipIds(), command.totalBonusCents(), command.allocations());
        String idempotencyHash = hash(command.idempotencyKey());
        Existing active = activeRequest(actor.organizationId(), command.customerId());
        if (active != null && "APPROVED".equals(active.status())) {
            if (idempotencyHash.equals(active.idempotencyKeyHash())) return active.id();
            throw ApiException.conflict("CUSTOMER_ALREADY_WON", "该客户已成单");
        }

        UUID requestId;
        String previous;
        if (active == null) {
            requestId = UUID.randomUUID();
            previous = null;
            try {
                jdbc.update("""
                        insert into customer_deal_requests
                        (id,organization_id,customer_id,requested_by_membership_id,status,source,
                         decided_by_membership_id,requested_at,decided_at,idempotency_key_hash,
                         confirmation_channel,confirmed_by_membership_id,confirmed_at,order_amount_cents,bonus_total_cents,
                         attribution_state,created_at,updated_at,version)
                        values (?,?,?,?,'APPROVED',?, ?,now(),now(),?, ?,?,now(),?,?,
                                'COMPLETE',now(),now(),0)
                        """, requestId, actor.organizationId(), command.customerId(), actor.membershipId(),
                        confirmationChannel, actor.membershipId(), idempotencyHash, confirmationChannel,
                        actor.membershipId(),
                        command.orderAmountCents(), command.totalBonusCents());
            } catch (DuplicateKeyException exception) {
                throw ApiException.conflict("DEAL_CONFIRMATION_CONFLICT", "该客户的成单状态已由其他请求处理");
            }
        } else {
            requestId = active.id();
            previous = "PENDING";
            int changed = jdbc.update("""
                    update customer_deal_requests
                    set status='APPROVED',decided_by_membership_id=?,decided_at=now(),
                        source=?,idempotency_key_hash=?,confirmation_channel=?,confirmed_by_membership_id=?,confirmed_at=now(),
                        order_amount_cents=?,bonus_total_cents=?,attribution_state='COMPLETE',
                        updated_at=now(),version=version+1
                    where id=? and organization_id=? and status='PENDING'
                    """, actor.membershipId(), confirmationChannel, idempotencyHash, confirmationChannel,
                    actor.membershipId(), command.orderAmountCents(), command.totalBonusCents(), requestId,
                    actor.organizationId());
            if (changed != 1) throw ApiException.conflict("DEAL_CONFIRMATION_CONFLICT", "成单状态已变化，请重新查询");
        }

        Instant confirmedAt = jdbc.queryForObject("select confirmed_at from customer_deal_requests where id=?",
                Timestamp.class, requestId).toInstant();
        insertAttribution(actor, requestId, allocations, command.primaryMembershipId(), confirmedAt);
        appendEvent(actor, requestId, command.customerId(), "FEISHU_CONFIRMED", previous, "APPROVED",
                Map.of("channel", confirmationChannel, "participantCount", allocations.size(),
                        "orderAmountCents", command.orderAmountCents()));
        appendTimeline(actor, command.customerId(), requestId, allocations.keySet());
        enqueueBroadcast(actor.organizationId(), requestId, buildDealBroadcast(actor.organizationId(), requestId));
        String auditAction = "FEISHU_BOT".equals(confirmationChannel)
                ? "CUSTOMER_DEAL_CONFIRMED_BY_FEISHU" : "CUSTOMER_DEAL_CONFIRMED_BY_FEISHU_WEB";
        audit.record(actor, auditAction, "CUSTOMER_DEAL_REQUEST",
                requestId.toString(), "SUCCESS", Map.of("customerId", command.customerId().toString(),
                        "participantCount", allocations.size(), "orderAmountCents", command.orderAmountCents(),
                        "bonusTotalCents", command.totalBonusCents()));
        return requestId;
    }

    @Transactional(readOnly = true)
    public FeishuDealBotDtos.ConfirmationPage list(String status, boolean legacyOnly, int page, int size) {
        CurrentActor actor = requireAdmin();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        String normalized = normalizeStatus(status);
        String predicate = (normalized == null ? "" : " and request.status='" + normalized + "'")
                + (legacyOnly ? " and request.status='APPROVED' and request.attribution_state='NEEDS_BACKFILL'" : "");
        Long total = jdbc.queryForObject("select count(*) from customer_deal_requests request where request.organization_id=?"
                + predicate, Long.class, actor.organizationId());
        List<FeishuDealBotDtos.ConfirmationResponse> content = jdbc.query("""
                select request.id,request.customer_id,customer.full_name customer_name,request.status,request.source,
                       request.confirmation_channel,request.confirmed_at,request.order_amount_cents,
                       request.bonus_total_cents,
                       request.attribution_state,request.version
                from customer_deal_requests request join customers customer on customer.id=request.customer_id
                where request.organization_id=?
                """ + predicate + " order by coalesce(request.confirmed_at,request.requested_at) desc limit ? offset ?",
                (rs, row) -> mapConfirmation(rs, actor.organizationId()), actor.organizationId(), safeSize,
                safePage * safeSize);
        long count = total == null ? 0 : total;
        return new FeishuDealBotDtos.ConfirmationPage(content, safePage, safeSize, count,
                count == 0 ? 0 : (int) Math.ceil((double) count / safeSize));
    }

    @Transactional
    public FeishuDealBotDtos.ConfirmationResponse reverse(UUID requestId,
                                                           FeishuDealBotDtos.ReversalRequest body) {
        CurrentActor actor = requireAdmin();
        ConfirmationRow request = lockConfirmation(actor.organizationId(), requestId);
        if (!"APPROVED".equals(request.status())) {
            throw ApiException.conflict("DEAL_CONFIRMATION_NOT_REVERSIBLE", "只有有效成单可以冲正");
        }
        if (request.version() != body.expectedVersion()) {
            throw ApiException.conflict("DEAL_CONFIRMATION_VERSION_CONFLICT", "成单记录已更新，请刷新后重试");
        }
        int changed = jdbc.update("""
                update customer_deal_requests set status='REVERSED',reversed_by_membership_id=?,reversed_at=now(),
                    reversal_reason=?,updated_at=now(),version=version+1
                where id=? and organization_id=? and status='APPROVED' and version=?
                """, actor.membershipId(), body.reason().trim(), requestId, actor.organizationId(), body.expectedVersion());
        if (changed != 1) throw ApiException.conflict("DEAL_CONFIRMATION_VERSION_CONFLICT", "成单记录已更新，请刷新后重试");
        jdbc.update("""
                insert into customer_deal_bonus_ledger
                (id,organization_id,request_id,participant_id,membership_id,entry_type,amount_cents,
                 effective_at,reversed_entry_id,created_by_membership_id)
                select gen_random_uuid(),credit.organization_id,credit.request_id,credit.participant_id,
                       credit.membership_id,'REVERSAL',-credit.amount_cents,now(),credit.id,?
                from customer_deal_bonus_ledger credit
                where credit.request_id=? and credit.entry_type='CREDIT'
                on conflict do nothing
                """, actor.membershipId(), requestId);
        appendEvent(actor, requestId, request.customerId(), "REVERSED", "APPROVED", "REVERSED",
                Map.of("reason", body.reason().trim()));
        enqueueCorrectionIfConfigured(actor.organizationId(), requestId, request.customerName(), body.reason().trim());
        audit.record(actor, "CUSTOMER_DEAL_REVERSED", "CUSTOMER_DEAL_REQUEST", requestId.toString(), "SUCCESS",
                Map.of("customerId", request.customerId().toString(), "reason", body.reason().trim()));
        return getConfirmation(actor.organizationId(), requestId);
    }

    @Transactional
    public FeishuDealBotDtos.ConfirmationResponse completeAttribution(
            UUID requestId, FeishuDealBotDtos.CompleteAttributionRequest body) {
        CurrentActor actor = requireAdmin();
        ConfirmationRow request = lockConfirmation(actor.organizationId(), requestId);
        if (!"APPROVED".equals(request.status()) || !"NEEDS_BACKFILL".equals(request.attributionState())) {
            throw ApiException.conflict("DEAL_ATTRIBUTION_NOT_REQUIRED", "该成单无需补录或已完成补录");
        }
        if (request.version() != body.expectedVersion()) {
            throw ApiException.conflict("DEAL_CONFIRMATION_VERSION_CONFLICT", "成单记录已更新，请刷新后重试");
        }
        Map<UUID, Long> requested = new LinkedHashMap<>();
        for (FeishuDealBotDtos.AllocationRequest allocation : body.allocations()) {
            if (allocation.bonusCents() == null) throw ApiException.badRequest("DEAL_ALLOCATION_INVALID", "奖金分配不能为空");
            if (requested.put(allocation.membershipId(), allocation.bonusCents()) != null) {
                throw ApiException.badRequest("DEAL_PARTICIPANT_DUPLICATE", "成单参与人不能重复");
            }
        }
        LinkedHashMap<UUID, Long> allocations = validateAttribution(actor, body.primaryMembershipId(),
                body.assistantMembershipIds(), body.totalBonusCents(), requested);
        int changed = jdbc.update("""
                update customer_deal_requests set order_amount_cents=coalesce(?,order_amount_cents),
                    bonus_total_cents=?,attribution_state='COMPLETE',
                    confirmation_channel=coalesce(confirmation_channel,'LEGACY'),
                    confirmed_at=coalesce(confirmed_at,decided_at,requested_at),updated_at=now(),version=version+1
                where id=? and organization_id=? and status='APPROVED' and attribution_state='NEEDS_BACKFILL'
                  and version=?
                """, body.orderAmountCents(), body.totalBonusCents(), requestId, actor.organizationId(),
                body.expectedVersion());
        if (changed != 1) throw ApiException.conflict("DEAL_CONFIRMATION_VERSION_CONFLICT", "成单记录已更新，请刷新后重试");
        Instant effective = request.confirmedAt() == null ? Instant.now() : request.confirmedAt();
        insertAttribution(actor, requestId, allocations, body.primaryMembershipId(), effective);
        appendEvent(actor, requestId, request.customerId(), "ATTRIBUTION_COMPLETED", "APPROVED", "APPROVED",
                Map.of("participantCount", allocations.size(), "bonusTotalCents", body.totalBonusCents(),
                        "orderAmountCents", body.orderAmountCents() == null ? 0 : body.orderAmountCents()));
        audit.record(actor, "CUSTOMER_DEAL_ATTRIBUTION_COMPLETED", "CUSTOMER_DEAL_REQUEST",
                requestId.toString(), "SUCCESS", Map.of("customerId", request.customerId().toString()));
        return getConfirmation(actor.organizationId(), requestId);
    }

    @Transactional(readOnly = true)
    public FeishuDealBotDtos.LeaderboardResponse leaderboard(LocalDate date) {
        CurrentActor actor = requireAdmin();
        return leaderboardFor(actor.organizationId(), date == null ? LocalDate.now(BUSINESS_ZONE) : date);
    }

    @Transactional(readOnly = true)
    public FeishuDealBotDtos.LeaderboardResponse leaderboardForCurrentActor(LocalDate date) {
        CurrentActor actor = actors.require();
        return leaderboardFor(actor.organizationId(), date == null ? LocalDate.now(BUSINESS_ZONE) : date);
    }

    @Transactional(readOnly = true)
    public FeishuDealBotDtos.DealSummaryResponse dealSummary(UUID customerId) {
        CurrentActor actor = actors.require();
        customers.requireVisibleEntity(customerId, actor);
        return jdbc.query("""
                select request.id,request.customer_id,request.status,request.confirmation_channel,
                       request.confirmed_at,request.order_amount_cents,request.bonus_total_cents
                from customer_deal_requests request
                where request.organization_id=? and request.customer_id=?
                  and request.status in ('APPROVED','PENDING')
                order by case request.status when 'APPROVED' then 0 else 1 end,request.requested_at desc
                limit 1
                """, (rs, row) -> {
            UUID requestId = rs.getObject("id", UUID.class);
            List<FeishuDealBotDtos.ParticipantResponse> participants = jdbc.query("""
                    select membership_id,display_name_snapshot,participant_role,bonus_cents
                    from customer_deal_participants where organization_id=? and request_id=?
                    order by position_index,id
                    """, (person, index) -> new FeishuDealBotDtos.ParticipantResponse(
                    person.getObject("membership_id", UUID.class), person.getString("display_name_snapshot"),
                    person.getString("participant_role"), person.getLong("bonus_cents")),
                    actor.organizationId(), requestId);
            Timestamp confirmed = rs.getTimestamp("confirmed_at");
            return new FeishuDealBotDtos.DealSummaryResponse(requestId,
                    rs.getObject("customer_id", UUID.class), rs.getString("status"),
                    rs.getString("confirmation_channel"), confirmed == null ? null : confirmed.toInstant(),
                    rs.getObject("order_amount_cents") == null ? null : rs.getLong("order_amount_cents"),
                    rs.getObject("bonus_total_cents") == null ? 0 : rs.getLong("bonus_total_cents"), participants);
        }, actor.organizationId(), customerId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("DEAL_SUMMARY_NOT_FOUND", "该客户暂无成单记录"));
    }

    public FeishuDealBotDtos.LeaderboardResponse leaderboardFor(UUID organizationId, LocalDate date) {
        LocalDate from = date.withDayOfMonth(1);
        LocalDate to = date.with(TemporalAdjusters.lastDayOfMonth());
        Instant fromInstant = from.atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant toExclusive = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Long totalDeals = jdbc.queryForObject("""
                select count(*) from customer_deal_requests
                where organization_id=? and status='APPROVED'
                """, Long.class, organizationId);
        Long totalBonus = jdbc.queryForObject("""
                select coalesce(sum(amount_cents),0) from customer_deal_bonus_ledger
                where organization_id=?
                """, Long.class, organizationId);
        Long totalOrderAmount = jdbc.queryForObject("""
                select coalesce(sum(order_amount_cents),0) from customer_deal_requests
                where organization_id=? and status='APPROVED'
                """, Long.class, organizationId);
        List<FeishuDealBotDtos.LeaderboardEntry> entries = jdbc.query("""
                select participant.membership_id,participant.display_name_snapshot,
                       count(*) deal_count,coalesce(sum(request.order_amount_cents),0) primary_order_amount,
                       coalesce(sum(participant.bonus_cents),0) primary_bonus,
                       max(request.confirmed_at) reached_at
                from customer_deal_participants participant
                join customer_deal_requests request on request.id=participant.request_id
                where participant.organization_id=? and participant.participant_role='PRIMARY'
                  and request.status='APPROVED' and request.confirmed_at>=? and request.confirmed_at<?
                group by participant.membership_id,participant.display_name_snapshot
                order by deal_count desc,primary_bonus desc,reached_at asc,participant.display_name_snapshot asc
                limit 6
                """, (rs, row) -> new FeishuDealBotDtos.LeaderboardEntry(row + 1,
                        rs.getObject("membership_id", UUID.class), rs.getString("display_name_snapshot"),
                        rs.getLong("deal_count"), rs.getLong("primary_order_amount"),
                        rs.getLong("primary_bonus"),
                        rs.getTimestamp("reached_at").toInstant()), organizationId, Timestamp.from(fromInstant),
                Timestamp.from(toExclusive));
        String text = renderLeaderboard(organizationId, from, to, totalDeals == null ? 0 : totalDeals,
                totalOrderAmount == null ? 0 : totalOrderAmount, totalBonus == null ? 0 : totalBonus, entries);
        return new FeishuDealBotDtos.LeaderboardResponse(from, to, totalDeals == null ? 0 : totalDeals,
                totalOrderAmount == null ? 0 : totalOrderAmount, totalBonus == null ? 0 : totalBonus, entries, text);
    }

    private LinkedHashMap<UUID, Long> validateAttribution(CurrentActor actor, UUID primary,
                                                           List<UUID> assistants, long total,
                                                           Map<UUID, Long> requested) {
        if (total < 0) throw ApiException.badRequest("DEAL_BONUS_INVALID", "奖金不能为负数");
        List<UUID> safeAssistants = assistants == null ? List.of() : assistants;
        if (safeAssistants.size() > 2) throw ApiException.badRequest("DEAL_ASSISTANT_LIMIT", "协助人最多两名");
        LinkedHashMap<UUID, Long> normalized = new LinkedHashMap<>();
        if (primary == null) throw ApiException.badRequest("DEAL_PRIMARY_REQUIRED", "必须选择主成单人");
        Set<UUID> ids = new HashSet<>();
        ids.add(primary);
        for (UUID assistant : safeAssistants) {
            if (assistant == null || !ids.add(assistant)) {
                throw ApiException.badRequest("DEAL_PARTICIPANT_DUPLICATE", "主成单人和协助人不能重复");
            }
        }
        if (ids.size() != safeAssistants.size() + 1) {
            throw ApiException.badRequest("DEAL_PARTICIPANT_DUPLICATE", "主成单人和协助人不能重复");
        }
        if (requested == null || !requested.keySet().equals(ids)) {
            throw ApiException.badRequest("DEAL_ALLOCATION_PARTICIPANTS_MISMATCH", "奖金分配人员必须与成单参与人一致");
        }
        long sum = 0;
        try {
            for (UUID id : ids) {
                Long amount = requested.get(id);
                if (amount == null || amount < 0) throw ApiException.badRequest("DEAL_ALLOCATION_INVALID", "奖金分配不能为负数");
                sum = Math.addExact(sum, amount);
            }
        } catch (ArithmeticException overflow) {
            throw ApiException.badRequest("DEAL_BONUS_TOO_LARGE", "奖金数值超出系统可存储范围");
        }
        if (sum != total) throw ApiException.badRequest("DEAL_ALLOCATION_SUM_MISMATCH", "个人奖金合计必须严格等于总奖金");
        Map<UUID, Person> people = eligiblePeople(actor.organizationId(), ids);
        if (people.size() != ids.size()) throw ApiException.badRequest("DEAL_PARTICIPANT_INELIGIBLE", "成单参与人已停用或无客户维护权限");
        normalized.put(primary, requested.get(primary));
        for (UUID assistant : safeAssistants) normalized.put(assistant, requested.get(assistant));
        return normalized;
    }

    private Map<UUID, Person> eligiblePeople(UUID organizationId, Set<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(organizationId);
        args.addAll(ids);
        Map<UUID, Person> result = new HashMap<>();
        jdbc.query("""
                select membership.id,account.display_name,
                       coalesce(string_agg(distinct team.name,'、' order by team.name),'') team_names
                from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                join membership_roles assignment on assignment.membership_id=membership.id
                join roles role on role.id=assignment.role_id and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER')
                left join team_members team_member on team_member.membership_id=membership.id
                left join teams team on team.id=team_member.team_id and team.active=true
                where membership.organization_id=? and membership.status='ACTIVE' and membership.id in (
                """ + placeholders + ") group by membership.id,account.display_name",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> result.put(rs.getObject("id", UUID.class),
                        new Person(rs.getString("display_name"), rs.getString("team_names"))), args.toArray());
        return result;
    }

    private void insertAttribution(CurrentActor actor, UUID requestId, LinkedHashMap<UUID, Long> allocations,
                                   UUID primaryId, Instant effectiveAt) {
        Map<UUID, Person> people = eligiblePeople(actor.organizationId(), allocations.keySet());
        int index = 0;
        for (Map.Entry<UUID, Long> entry : allocations.entrySet()) {
            UUID participantId = UUID.randomUUID();
            Person person = people.get(entry.getKey());
            String role = entry.getKey().equals(primaryId) ? "PRIMARY" : "ASSISTANT";
            jdbc.update("""
                    insert into customer_deal_participants
                    (id,organization_id,request_id,membership_id,participant_role,display_name_snapshot,
                     team_name_snapshot,bonus_cents,position_index)
                    values (?,?,?,?,?,?,?,?,?)
                    """, participantId, actor.organizationId(), requestId, entry.getKey(), role,
                    person.name(), person.teamNames(), entry.getValue(), index++);
            jdbc.update("""
                    insert into customer_deal_bonus_ledger
                    (id,organization_id,request_id,participant_id,membership_id,entry_type,amount_cents,
                     effective_at,created_by_membership_id)
                    values (?,?,?,?,?,'CREDIT',?,?,?)
                    """, UUID.randomUUID(), actor.organizationId(), requestId, participantId, entry.getKey(),
                    entry.getValue(), Timestamp.from(effectiveAt), actor.membershipId());
        }
    }

    private Existing activeRequest(UUID organizationId, UUID customerId) {
        return jdbc.query("""
                select id,status,idempotency_key_hash from customer_deal_requests
                where organization_id=? and customer_id=? and status in ('PENDING','APPROVED')
                order by case status when 'APPROVED' then 0 else 1 end for update
                """, (rs, row) -> new Existing(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("idempotency_key_hash")),
                organizationId, customerId).stream().findFirst().orElse(null);
    }

    private ConfirmationRow lockConfirmation(UUID organizationId, UUID requestId) {
        return jdbc.query("""
                select request.id,request.customer_id,customer.full_name customer_name,request.status,
                       request.attribution_state,request.confirmed_at,request.version
                from customer_deal_requests request join customers customer on customer.id=request.customer_id
                where request.organization_id=? and request.id=? for update of request
                """, (rs, row) -> new ConfirmationRow(rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class), rs.getString("customer_name"),
                        rs.getString("status"), rs.getString("attribution_state"),
                        instant(rs.getTimestamp("confirmed_at")), rs.getLong("version")), organizationId, requestId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("DEAL_CONFIRMATION_NOT_FOUND", "成单记录不存在"));
    }

    private FeishuDealBotDtos.ConfirmationResponse getConfirmation(UUID organizationId, UUID requestId) {
        return jdbc.query("""
                select request.id,request.customer_id,customer.full_name customer_name,request.status,request.source,
                       request.confirmation_channel,request.confirmed_at,request.order_amount_cents,
                       request.bonus_total_cents,
                       request.attribution_state,request.version
                from customer_deal_requests request join customers customer on customer.id=request.customer_id
                where request.organization_id=? and request.id=?
                """, (rs, row) -> mapConfirmation(rs, organizationId), organizationId, requestId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("DEAL_CONFIRMATION_NOT_FOUND", "成单记录不存在"));
    }

    private FeishuDealBotDtos.ConfirmationResponse mapConfirmation(ResultSet rs, UUID organizationId) throws SQLException {
        UUID requestId = rs.getObject("id", UUID.class);
        List<FeishuDealBotDtos.ParticipantResponse> participants = jdbc.query("""
                select membership_id,display_name_snapshot,participant_role,bonus_cents
                from customer_deal_participants where organization_id=? and request_id=?
                order by position_index,id
                """, (row, index) -> new FeishuDealBotDtos.ParticipantResponse(
                row.getObject("membership_id", UUID.class), row.getString("display_name_snapshot"),
                row.getString("participant_role"), row.getLong("bonus_cents")), organizationId, requestId);
        Timestamp confirmed = rs.getTimestamp("confirmed_at");
        return new FeishuDealBotDtos.ConfirmationResponse(requestId, rs.getObject("customer_id", UUID.class),
                rs.getString("customer_name"), rs.getString("status"), rs.getString("source"),
                rs.getString("confirmation_channel"), confirmed == null ? null : confirmed.toInstant(),
                rs.getObject("order_amount_cents") == null ? null : rs.getLong("order_amount_cents"),
                rs.getObject("bonus_total_cents") == null ? 0 : rs.getLong("bonus_total_cents"),
                "NEEDS_BACKFILL".equals(rs.getString("attribution_state")), participants, rs.getLong("version"));
    }

    private String buildDealBroadcast(UUID organizationId, UUID requestId) {
        Map<String, Object> deal = jdbc.queryForMap("""
                select customer.full_name customer_name,settings.broadcast_title,request.order_amount_cents,
                       request.bonus_total_cents
                from customer_deal_requests request join customers customer on customer.id=request.customer_id
                join feishu_deal_bot_settings settings on settings.organization_id=request.organization_id
                where request.id=? and request.organization_id=?
                """, requestId, organizationId);
        List<Map<String, Object>> people = jdbc.queryForList("""
                select display_name_snapshot,participant_role,bonus_cents from customer_deal_participants
                where request_id=? order by position_index,id
                """, requestId);
        String primary = people.stream().filter(p -> "PRIMARY".equals(p.get("participant_role")))
                .map(p -> String.valueOf(p.get("display_name_snapshot"))).findFirst().orElse("未记录");
        String assistants = people.stream().filter(p -> "ASSISTANT".equals(p.get("participant_role")))
                .map(p -> String.valueOf(p.get("display_name_snapshot"))).reduce((a, b) -> a + "、" + b).orElse("无");
        StringBuilder text = new StringBuilder(String.valueOf(deal.get("broadcast_title"))).append("\n\n")
                .append("客户：").append(deal.get("customer_name")).append("\n")
                .append("主成单人：").append(primary).append("\n")
                .append("协助：").append(assistants).append("\n")
                .append("订单金额：¥").append(formatCents(((Number) deal.get("order_amount_cents")).longValue()))
                .append("\n绩效奖金：\n");
        for (Map<String, Object> person : people) {
            text.append(person.get("display_name_snapshot")).append("：¥")
                    .append(formatCents(((Number) person.get("bonus_cents")).longValue())).append("\n");
        }
        return text.append("合计：¥").append(formatCents(((Number) deal.get("bonus_total_cents")).longValue())).toString();
    }

    private String renderLeaderboard(UUID organizationId, LocalDate from, LocalDate to, long totalDeals,
                                     long totalOrderAmount, long totalBonus,
                                     List<FeishuDealBotDtos.LeaderboardEntry> entries) {
        List<Map<String, Object>> settingsRows = jdbc.queryForList("""
                select leaderboard_title,leaderboard_footer from feishu_deal_bot_settings where organization_id=?
                """, organizationId);
        Map<String, Object> settings = settingsRows.isEmpty() ? Map.of(
                "leaderboard_title", "🌹英格科技我为你操碎了心🌹",
                "leaderboard_footer", "👀看看又是哪个小笨蛋饿死了👀\n🔨每日任务：激活 登记 对接🔨"
        ) : settingsRows.getFirst();
        String[] medals = {"🥇", "🥈", "🥉", "4️⃣", "5️⃣", "6️⃣"};
        StringBuilder text = new StringBuilder(String.valueOf(settings.get("leaderboard_title"))).append("\n")
                .append("🐶累计已拯救 ").append(totalDeals).append(" 名迷茫少年🐶\n")
                .append("---------------------\n🗓").append(DATE.format(from)).append("-")
                .append(DATE.format(to)).append("开单功臣🗓\n\n");
        for (int index = 0; index < 6; index++) {
            text.append(medals[index]).append(" ");
            if (index < entries.size()) {
                FeishuDealBotDtos.LeaderboardEntry entry = entries.get(index);
                text.append(entry.displayName()).append(" 累计已开").append(entry.dealCount()).append("单")
                        .append(" · 成交额¥").append(formatCents(entry.primaryOrderAmountCents()))
                        .append(" · 奖金¥").append(formatCents(entry.primaryBonusCents()));
            } else text.append("暂无");
            text.append("\n");
        }
        return text.append("---\n💵累计成交额 ¥").append(formatCents(totalOrderAmount)).append("\n")
                .append("💰累计已发 ").append(formatCents(totalBonus)).append(" 元奖金💰\n\n")
                .append(settings.get("leaderboard_footer")).toString();
    }

    private void enqueueBroadcast(UUID organizationId, UUID requestId, String text) {
        enqueue(organizationId, requestId, "DEAL_BROADCAST", text);
    }

    private void enqueueCorrectionIfConfigured(UUID organizationId, UUID requestId, String customerName, String reason) {
        Long configured = jdbc.queryForObject("""
                select count(*) from feishu_deal_bot_settings
                where organization_id=? and enabled=true and broadcast_chat_id is not null
                """, Long.class, organizationId);
        if (configured != null && configured > 0) {
            enqueue(organizationId, requestId, "DEAL_REVERSAL",
                    "⚠️ 成单更正通知\n\n客户：" + customerName + "\n状态：已冲正\n原因：" + reason);
        }
    }

    public UUID enqueueLeaderboard(UUID organizationId, LocalDate date) {
        FeishuDealBotDtos.LeaderboardResponse board = leaderboardFor(organizationId, date);
        return enqueue(organizationId, null, "DAILY_LEADERBOARD", board.previewText());
    }

    private UUID enqueue(UUID organizationId, UUID aggregateId, String purpose, String text) {
        Map<String, Object> setting = jdbc.queryForMap("""
                select integration_id,broadcast_chat_id from feishu_deal_bot_settings
                where organization_id=? and enabled=true and broadcast_chat_id is not null
                """, organizationId);
        UUID outboxId = UUID.randomUUID();
        UUID messageUuid = UUID.nameUUIDFromBytes((organizationId + ":" + purpose + ":" +
                (aggregateId == null ? LocalDate.now(BUSINESS_ZONE) : aggregateId)).getBytes(StandardCharsets.UTF_8));
        int inserted = jdbc.update("""
                insert into feishu_deal_message_outbox
                (id,integration_id,organization_id,chat_id,message_type,payload,message_uuid,purpose,aggregate_id)
                values (?,?,?,?, 'text',cast(? as jsonb),?,?,?) on conflict (integration_id,message_uuid) do nothing
                """, outboxId, setting.get("integration_id"), organizationId, setting.get("broadcast_chat_id"),
                json(Map.of("text", text)), messageUuid, purpose, aggregateId);
        if (inserted == 0) {
            outboxId = jdbc.queryForObject("""
                    select id from feishu_deal_message_outbox where integration_id=? and message_uuid=?
                    """, UUID.class, setting.get("integration_id"), messageUuid);
        }
        return outboxId;
    }

    private void appendTimeline(CurrentActor actor, UUID customerId, UUID requestId, Set<UUID> participants) {
        String people = jdbc.queryForList("select display_name from user_accounts account join memberships membership " +
                "on membership.user_id=account.id where membership.id in (" +
                String.join(",", java.util.Collections.nCopies(participants.size(), "?")) + ") order by display_name",
                String.class, participants.toArray()).stream().reduce((a, b) -> a + "、" + b).orElse("未记录");
        jdbc.update("""
                insert into interactions
                (id,organization_id,customer_id,contacted_at,channel,summary,details,created_by_membership_id,created_at)
                values (?,?,?,now(),'系统','成单已确认',?,?,now())
                """, UUID.randomUUID(), actor.organizationId(), customerId,
                "飞书成单确认；参与人：" + people + "；成单记录：" + requestId,
                actor.membershipId());
    }

    private void appendEvent(CurrentActor actor, UUID requestId, UUID customerId, String type,
                             String previous, String next, Map<String, Object> details) {
        jdbc.update("""
                insert into customer_deal_request_events
                (id,organization_id,request_id,customer_id,actor_membership_id,event_type,
                 previous_status,new_status,details,created_at)
                values (?,?,?,?,?,?,?,?,cast(? as jsonb),now())
                """, UUID.randomUUID(), actor.organizationId(), requestId, customerId, actor.membershipId(),
                type, previous, next, json(details));
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return actor;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("PENDING", "APPROVED", "REJECTED", "REVERSED").contains(normalized)) {
            throw ApiException.badRequest("DEAL_CONFIRMATION_STATUS_INVALID", "成单状态无效");
        }
        return normalized;
    }

    private String hash(String key) {
        String safe = key == null ? UUID.randomUUID().toString() : key;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(safe.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize deal event", exception);
        }
    }

    private String formatCents(long cents) {
        return java.math.BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString();
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record FeishuConfirmation(UUID customerId, UUID primaryMembershipId,
                                     List<UUID> assistantMembershipIds, long orderAmountCents,
                                     long totalBonusCents,
                                     Map<UUID, Long> allocations, String idempotencyKey) {}
    private record Existing(UUID id, String status, String idempotencyKeyHash) {}
    private record Person(String name, String teamNames) {}
    private record ConfirmationRow(UUID id, UUID customerId, String customerName, String status,
                                   String attributionState, Instant confirmedAt, long version) {}
}
