package com.yingge.cloud.feishu.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.deal.application.DealConfirmationService;
import com.yingge.cloud.feishu.api.FeishuDealBotDtos;
import com.yingge.cloud.feishu.api.FeishuWorkbenchDtos;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FeishuDealWorkbenchService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentActorProvider actors;
    private final FeishuAuthService auth;
    private final CustomerService customers;
    private final DealConfirmationService confirmations;

    public FeishuDealWorkbenchService(JdbcTemplate jdbc, ObjectMapper json, CurrentActorProvider actors,
                                      FeishuAuthService auth, CustomerService customers,
                                      DealConfirmationService confirmations) {
        this.jdbc = jdbc;
        this.json = json;
        this.actors = actors;
        this.auth = auth;
        this.customers = customers;
        this.confirmations = confirmations;
    }

    @Transactional(readOnly = true)
    public List<FeishuWorkbenchDtos.DealMemberResponse> members(String search) {
        FeishuAuthService.BindingContext binding = requireWritableBinding();
        String needle = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.query("""
                select membership.id,account.display_name,
                       coalesce(string_agg(distinct team.name, '、' order by team.name),'') team_names
                from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                left join team_members team_member on team_member.membership_id=membership.id
                left join teams team on team.id=team_member.team_id and team.active=true
                where membership.organization_id=? and membership.status='ACTIVE'
                  and exists (
                    select 1 from membership_roles assignment join roles role on role.id=assignment.role_id
                    where assignment.membership_id=membership.id
                      and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER')
                  )
                  and (cast(? as text) is null or lower(account.display_name) like ?)
                group by membership.id,account.display_name
                order by account.display_name,membership.id limit 100
                """, (rs, row) -> new FeishuWorkbenchDtos.DealMemberResponse(
                rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("team_names")),
                binding.organizationId(), needle, needle);
    }

    @Transactional(readOnly = true)
    public CustomerDtos.CollaborationOverview collaboration(UUID customerId) {
        requireWritableBinding();
        return customers.collaboration(customerId);
    }

    @Transactional
    public CustomerDtos.Response transfer(UUID customerId, CustomerDtos.TransferOwnershipRequest body) {
        requireWritableBinding();
        return customers.transferOwnership(customerId, body);
    }

    @Transactional
    public FeishuWorkbenchDtos.DealDraftResponse currentDraft() {
        FeishuAuthService.BindingContext binding = requireWritableBinding();
        expire(binding);
        DraftRow row = active(binding);
        return row == null ? null : response(row);
    }

    @Transactional
    public FeishuWorkbenchDtos.DealDraftResponse createDraft(UUID customerId) {
        FeishuAuthService.BindingContext binding = requireWritableBinding();
        requireDealBotReady(binding.integrationId());
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actors.require());
        customers.requireProfileEditable(customer, actors.require());
        if (customer.getArchivedAt() != null) {
            throw ApiException.conflict("ARCHIVED_CUSTOMER_DEAL_FORBIDDEN", "归档客户不能确认成单");
        }
        if ("WON".equals(customers.get(customerId).dealState())) {
            throw ApiException.conflict("CUSTOMER_ALREADY_WON", "该客户已成单");
        }
        expire(binding);
        DraftRow existing = active(binding);
        if (existing != null) {
            if (existing.customerId().equals(customerId)) return response(existing);
            throw ApiException.conflict("FEISHU_DEAL_WEB_DRAFT_EXISTS", "当前已有未完成草稿，请先确认或取消");
        }
        long defaultBonus = defaultBonus(binding.integrationId());
        LinkedHashMap<UUID, Long> allocations = new LinkedHashMap<>();
        allocations.put(binding.membershipId(), defaultBonus);
        UUID id = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(30 * 60);
        try {
            jdbc.update("""
                    insert into feishu_deal_web_drafts
                    (id,integration_id,organization_id,created_by_membership_id,customer_id,
                     primary_membership_id,bonus_total_cents,allocations,expires_at)
                    values (?,?,?,?,?,?,?,cast(? as jsonb),?)
                    """, id, binding.integrationId(), binding.organizationId(), binding.membershipId(),
                    customerId, binding.membershipId(), defaultBonus, writeAllocations(allocations),
                    Timestamp.from(expiresAt));
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("FEISHU_DEAL_WEB_DRAFT_EXISTS", "当前已有未完成草稿，请刷新后继续");
        }
        return response(require(id, binding, false));
    }

    @Transactional
    public FeishuWorkbenchDtos.DealDraftResponse updateDraft(
            UUID draftId, FeishuWorkbenchDtos.DealDraftUpdateRequest body) {
        FeishuAuthService.BindingContext binding = requireWritableBinding();
        DraftRow draft = require(draftId, binding, true);
        requireActive(draft);
        if (draft.version() != body.expectedVersion()) {
            throw ApiException.conflict("FEISHU_DEAL_WEB_DRAFT_VERSION_CONFLICT", "成单草稿已在其他页面更新，请刷新");
        }
        LinkedHashMap<UUID, Long> allocations = validateDraft(
                binding, body.primaryMembershipId(), body.assistantMembershipIds(),
                body.totalBonusCents(), body.allocations());
        int changed = jdbc.update("""
                update feishu_deal_web_drafts set primary_membership_id=?,assistant_membership_ids=?,
                    order_amount_cents=?,bonus_total_cents=?,allocations=cast(? as jsonb),
                    expires_at=now()+interval '30 minutes',updated_at=now(),version=version+1
                where id=? and organization_id=? and created_by_membership_id=? and status='ACTIVE' and version=?
                """, body.primaryMembershipId(), body.assistantMembershipIds().toArray(UUID[]::new),
                body.orderAmountCents(), body.totalBonusCents(), writeAllocations(allocations), draftId,
                binding.organizationId(), binding.membershipId(), body.expectedVersion());
        if (changed != 1) {
            throw ApiException.conflict("FEISHU_DEAL_WEB_DRAFT_VERSION_CONFLICT", "成单草稿已在其他页面更新，请刷新");
        }
        return response(require(draftId, binding, false));
    }

    @Transactional
    public void cancelDraft(UUID draftId) {
        FeishuAuthService.BindingContext binding = requireWritableBinding();
        int changed = jdbc.update("""
                update feishu_deal_web_drafts set status='CANCELLED',updated_at=now(),version=version+1
                where id=? and organization_id=? and created_by_membership_id=? and status='ACTIVE'
                """, draftId, binding.organizationId(), binding.membershipId());
        if (changed == 0) {
            throw ApiException.notFound("FEISHU_DEAL_WEB_DRAFT_NOT_FOUND", "成单草稿不存在或已结束");
        }
    }

    @Transactional
    public FeishuWorkbenchDtos.DealConfirmationResult confirm(
            UUID draftId, long expectedVersion, String idempotencyKey) {
        FeishuAuthService.BindingContext binding = requireWritableBinding();
        requireDealBotReady(binding.integrationId());
        String key = idempotencyKey == null ? "" : idempotencyKey.trim();
        if (key.length() < 16 || key.length() > 180) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "幂等键长度应为16–180个字符");
        }
        DraftRow draft = require(draftId, binding, true);
        if ("CONFIRMED".equals(draft.status()) && draft.confirmedRequestId() != null) {
            return result(draft.customerId(), draft.confirmedRequestId());
        }
        requireActive(draft);
        if (draft.version() != expectedVersion) {
            throw ApiException.conflict("FEISHU_DEAL_WEB_DRAFT_VERSION_CONFLICT", "成单草稿已更新，请刷新后确认");
        }
        if (draft.orderAmountCents() <= 0 || draft.bonusTotalCents() <= 0) {
            throw ApiException.badRequest("DEAL_AMOUNT_REQUIRED", "订单金额和奖金总额都必须大于0");
        }
        Map<UUID, Long> allocations = allocations(draft);
        validateDraft(binding, draft.primaryMembershipId(), draft.assistantMembershipIds(),
                draft.bonusTotalCents(), allocations.entrySet().stream()
                        .map(entry -> new FeishuWorkbenchDtos.DealAllocationRequest(entry.getKey(), entry.getValue()))
                        .toList());
        UUID requestId = confirmations.confirmFromFeishuWeb(new DealConfirmationService.FeishuConfirmation(
                draft.customerId(), draft.primaryMembershipId(), draft.assistantMembershipIds(),
                draft.orderAmountCents(), draft.bonusTotalCents(), allocations, key));
        jdbc.update("""
                update feishu_deal_web_drafts set status='CONFIRMED',confirmed_request_id=?,updated_at=now(),
                    version=version+1 where id=? and status='ACTIVE'
                """, requestId, draft.id());
        return result(draft.customerId(), requestId);
    }

    @Transactional(readOnly = true)
    public FeishuWorkbenchDtos.DealConfirmationResult confirmation(UUID requestId) {
        FeishuAuthService.BindingContext binding = requireWritableBinding();
        DraftRow draft = jdbc.query("""
                select * from feishu_deal_web_drafts
                where organization_id=? and created_by_membership_id=? and confirmed_request_id=?
                """, this::mapDraft, binding.organizationId(), binding.membershipId(), requestId).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound(
                        "FEISHU_DEAL_WEB_CONFIRMATION_NOT_FOUND", "成单确认记录不存在"));
        return result(draft.customerId(), requestId);
    }

    @Transactional(readOnly = true)
    public FeishuDealBotDtos.LeaderboardResponse leaderboard(String month) {
        requireWritableBinding();
        try {
            return confirmations.leaderboardForCurrentActor(month == null || month.isBlank()
                    ? java.time.LocalDate.now(BUSINESS_ZONE) : YearMonth.parse(month).atDay(1));
        } catch (DateTimeParseException exception) {
            throw ApiException.badRequest("DEAL_MONTH_INVALID", "月份格式应为 YYYY-MM");
        }
    }

    private FeishuWorkbenchDtos.DealConfirmationResult result(UUID customerId, UUID requestId) {
        FeishuDealBotDtos.DealSummaryResponse confirmation = confirmations.dealSummary(customerId);
        String status = jdbc.query("""
                select status from feishu_deal_message_outbox
                where aggregate_id=? and purpose='DEAL_BROADCAST' order by created_at desc limit 1
                """, (rs, row) -> rs.getString(1), requestId).stream().findFirst().orElse("NOT_CONFIGURED");
        return new FeishuWorkbenchDtos.DealConfirmationResult(confirmation, status);
    }

    private LinkedHashMap<UUID, Long> validateDraft(
            FeishuAuthService.BindingContext binding, UUID primary, List<UUID> assistants,
            long totalBonus, List<FeishuWorkbenchDtos.DealAllocationRequest> requested) {
        List<UUID> safeAssistants = assistants == null ? List.of() : assistants;
        if (safeAssistants.size() > 2) {
            throw ApiException.badRequest("DEAL_ASSISTANT_LIMIT", "成单协助人最多两名");
        }
        LinkedHashSet<UUID> participants = new LinkedHashSet<>();
        participants.add(primary);
        participants.addAll(safeAssistants);
        if (participants.contains(null) || participants.size() != safeAssistants.size() + 1) {
            throw ApiException.badRequest("DEAL_PARTICIPANT_DUPLICATE", "主成单人和协助人不能重复");
        }
        requireEligiblePeople(binding, participants);
        LinkedHashMap<UUID, Long> allocations = new LinkedHashMap<>();
        for (FeishuWorkbenchDtos.DealAllocationRequest allocation : requested) {
            if (allocations.put(allocation.membershipId(), allocation.bonusCents()) != null) {
                throw ApiException.badRequest("DEAL_PARTICIPANT_DUPLICATE", "奖金分配人员不能重复");
            }
        }
        if (!allocations.keySet().equals(participants)) {
            throw ApiException.badRequest("DEAL_ALLOCATION_PARTICIPANTS_MISMATCH", "必须为所有成单参与人分配奖金");
        }
        long sum;
        try {
            sum = allocations.values().stream().reduce(0L, Math::addExact);
        } catch (ArithmeticException exception) {
            throw ApiException.badRequest("DEAL_AMOUNT_INVALID", "奖金金额过大");
        }
        if (sum != totalBonus) {
            throw ApiException.badRequest("DEAL_ALLOCATION_SUM_MISMATCH", "个人奖金合计必须严格等于奖金总额");
        }
        return allocations;
    }

    private void requireEligiblePeople(FeishuAuthService.BindingContext binding, Set<UUID> requested) {
        if (requested.isEmpty()) return;
        Integer count = jdbc.queryForObject("""
                select count(distinct membership.id) from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                where membership.organization_id=? and membership.status='ACTIVE'
                  and membership.id in (%s)
                  and exists (
                    select 1 from membership_roles assignment join roles role on role.id=assignment.role_id
                    where assignment.membership_id=membership.id
                      and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER')
                  )
                """.formatted(String.join(",", java.util.Collections.nCopies(requested.size(), "?"))),
                Integer.class, joined(binding.organizationId(), requested));
        if (count == null || count != requested.size()) {
            throw ApiException.badRequest("FEISHU_DEAL_PERSON_INVALID", "成单参与人已停用、跨组织或无客户维护权限");
        }
    }

    private Object[] joined(UUID organizationId, Set<UUID> requested) {
        Object[] values = new Object[requested.size() + 1];
        values[0] = organizationId;
        int index = 1;
        for (UUID id : requested) values[index++] = id;
        return values;
    }

    private FeishuAuthService.BindingContext requireWritableBinding() {
        FeishuAuthService.BindingContext binding = auth.requireActiveBinding();
        if (!actors.require().canWriteCustomers()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FEISHU_DEAL_WEB_READ_ONLY", "当前账号无客户维护权限");
        }
        return binding;
    }

    private long defaultBonus(UUID integrationId) {
        Long value = jdbc.query("""
                select default_bonus_cents from feishu_deal_bot_settings
                where integration_id=? and enabled=true
                """, (rs, row) -> rs.getLong(1), integrationId).stream().findFirst().orElse(0L);
        return value == null ? 0 : value;
    }

    private void requireDealBotReady(UUID integrationId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from feishu_deal_bot_settings
                where integration_id=? and enabled=true
                  and submission_chat_id is not null and btrim(submission_chat_id)<>''
                  and broadcast_chat_id is not null and btrim(broadcast_chat_id)<>''
                  and privacy_acknowledged_at is not null
                  and financial_visibility_acknowledged_at is not null
                """, Integer.class, integrationId);
        if (count == null || count != 1) {
            throw ApiException.conflict("FEISHU_DEAL_WEB_NOT_READY", "快捷成单尚未完成群聊和金额可见性配置");
        }
    }

    private void expire(FeishuAuthService.BindingContext binding) {
        jdbc.update("""
                update feishu_deal_web_drafts set status='EXPIRED',updated_at=now(),version=version+1
                where integration_id=? and created_by_membership_id=? and status='ACTIVE' and expires_at<=now()
                """, binding.integrationId(), binding.membershipId());
    }

    private DraftRow active(FeishuAuthService.BindingContext binding) {
        return jdbc.query("""
                select * from feishu_deal_web_drafts
                where integration_id=? and organization_id=? and created_by_membership_id=?
                  and status='ACTIVE' and expires_at>now()
                """, this::mapDraft, binding.integrationId(), binding.organizationId(), binding.membershipId())
                .stream().findFirst().orElse(null);
    }

    private DraftRow require(UUID id, FeishuAuthService.BindingContext binding, boolean lock) {
        return jdbc.query("select * from feishu_deal_web_drafts where id=? and organization_id=? "
                        + "and created_by_membership_id=?" + (lock ? " for update" : ""),
                this::mapDraft, id, binding.organizationId(), binding.membershipId()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound(
                        "FEISHU_DEAL_WEB_DRAFT_NOT_FOUND", "成单草稿不存在"));
    }

    private void requireActive(DraftRow draft) {
        if (!"ACTIVE".equals(draft.status())) {
            throw ApiException.conflict("FEISHU_DEAL_WEB_DRAFT_FINALIZED", "成单草稿已结束");
        }
        if (!draft.expiresAt().isAfter(Instant.now())) {
            jdbc.update("update feishu_deal_web_drafts set status='EXPIRED',updated_at=now(),version=version+1 where id=?",
                    draft.id());
            throw ApiException.conflict("FEISHU_DEAL_WEB_DRAFT_EXPIRED", "成单草稿已过期，请重新发起");
        }
    }

    private FeishuWorkbenchDtos.DealDraftResponse response(DraftRow draft) {
        CustomerDtos.Response customer = customers.get(draft.customerId());
        Map<UUID, Long> allocationMap = allocations(draft);
        return new FeishuWorkbenchDtos.DealDraftResponse(
                draft.id(), draft.customerId(), customer.fullName(), customer.ownerMembershipId(),
                customer.ownerDisplayName(), draft.primaryMembershipId(), draft.assistantMembershipIds(),
                draft.orderAmountCents(), draft.bonusTotalCents(), allocationMap.entrySet().stream()
                        .map(entry -> new FeishuWorkbenchDtos.DealAllocationRequest(entry.getKey(), entry.getValue()))
                        .toList(), draft.status(), draft.confirmedRequestId(), draft.version(), draft.expiresAt());
    }

    private DraftRow mapDraft(ResultSet rs, int row) throws SQLException {
        Array assistantArray = rs.getArray("assistant_membership_ids");
        List<UUID> assistants = assistantArray == null ? List.of() : Arrays.asList((UUID[]) assistantArray.getArray());
        return new DraftRow(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("primary_membership_id", UUID.class), List.copyOf(assistants),
                rs.getLong("order_amount_cents"), rs.getLong("bonus_total_cents"),
                rs.getString("allocations"), rs.getString("status"),
                rs.getObject("confirmed_request_id", UUID.class), rs.getLong("version"),
                rs.getTimestamp("expires_at").toInstant());
    }

    private Map<UUID, Long> allocations(DraftRow draft) {
        try {
            Map<String, Long> stored = json.readValue(draft.allocations(), new TypeReference<>() {});
            LinkedHashMap<UUID, Long> result = new LinkedHashMap<>();
            stored.forEach((key, value) -> result.put(UUID.fromString(key), value));
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid Feishu deal web allocation", exception);
        }
    }

    private String writeAllocations(Map<UUID, Long> values) {
        LinkedHashMap<String, Long> stored = new LinkedHashMap<>();
        values.forEach((key, value) -> stored.put(key.toString(), value));
        try {
            return json.writeValueAsString(stored);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to store Feishu deal web allocation", exception);
        }
    }

    private record DraftRow(UUID id, UUID customerId, UUID primaryMembershipId,
                            List<UUID> assistantMembershipIds, long orderAmountCents,
                            long bonusTotalCents, String allocations, String status,
                            UUID confirmedRequestId, long version, Instant expiresAt) {}
}
