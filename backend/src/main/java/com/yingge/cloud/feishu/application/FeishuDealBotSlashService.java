package com.yingge.cloud.feishu.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.deal.application.DealConfirmationService;
import com.yingge.cloud.feishu.api.FeishuDealBotDtos;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FeishuDealBotSlashService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M-d HH:mm").withZone(BUSINESS_ZONE);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentActorProvider actors;
    private final CustomerService customers;
    private final DealConfirmationService confirmations;
    private final NotificationService notifications;
    private final String workbenchBaseUrl;

    public FeishuDealBotSlashService(JdbcTemplate jdbc, ObjectMapper json, CurrentActorProvider actors,
                                     CustomerService customers, DealConfirmationService confirmations,
                                     NotificationService notifications,
                                     @Value("${app.feishu.workbench-base-url:}") String workbenchBaseUrl) {
        this.jdbc = jdbc;
        this.json = json;
        this.actors = actors;
        this.customers = customers;
        this.confirmations = confirmations;
        this.notifications = notifications;
        this.workbenchBaseUrl = normalizeBaseUrl(workbenchBaseUrl);
    }

    @Transactional
    public void process(Context context, EventMessage message, String raw, String eventId) {
        CurrentActor actor = actors.require();
        String command = normalizeCommand(raw);
        String verb = commandVerb(command);
        String args = commandArgs(command);
        switch (verb) {
            case "/help" -> queueHelp(context, eventId);
            case "/crm" -> queueEntryCard(context, eventId, false);
            case "/deal-web" -> queueEntryCard(context, eventId, true);
            case "/search" -> search(context, message, args, eventId);
            case "/select" -> selectByNumber(context, message, args, eventId);
            case "/next" -> page(context, message, 1, eventId);
            case "/prev" -> page(context, message, -1, eventId);
            case "/new" -> startQuickCreate(context, message, args, eventId);
            case "/new-confirm" -> confirmQuickCreate(context, message, args, eventId);
            case "/current" -> queueCurrent(context, requireSession(context, message), eventId);
            case "/detail" -> queueDetail(context, requireSession(context, message), eventId);
            case "/members" -> queueMembers(context, message, args, eventId);
            case "/owner" -> requestOwnerTransfer(context, message, args, eventId);
            case "/deal" -> startDraft(context, requireSession(context, message), actor.membershipId(), false, eventId);
            case "/primary" -> modify(context, requireSession(context, message), "primary", args, eventId);
            case "/assist" -> modify(context, requireSession(context, message), "assist", args, eventId);
            case "/order" -> modify(context, requireSession(context, message), "order", args, eventId);
            case "/bonus" -> modify(context, requireSession(context, message), "bonus", args, eventId);
            case "/split" -> modify(context, requireSession(context, message), "split", args, eventId);
            case "/set" -> modify(context, requireSession(context, message), "set", args, eventId);
            case "/confirm" -> confirm(context, requireSession(context, message), eventId);
            case "/cancel" -> cancel(context, requireSession(context, message), eventId);
            case "/mine" -> queueMine(context, args, actor.membershipId(), eventId);
            case "/leaderboard" -> queueLeaderboard(context, args, eventId);
            case "/status" -> queueStatus(context, message, eventId);
            default -> throw ApiException.badRequest("FEISHU_DEAL_COMMAND_UNKNOWN", "无法识别该命令，请使用 /help 查看完整教程");
        }
    }

    @Transactional
    public boolean processCard(Context context, String chatId, UUID membershipId, Map<String, Object> value,
                               String eventId) {
        String action = String.valueOf(value.getOrDefault("action", ""));
        if (!action.startsWith("slash_")) return false;
        Session session = requireSession(context.integrationId(), chatId, membershipId);
        switch (action) {
            case "slash_select_customer" -> selectCustomer(context, session, parseUuid(value.get("customerId")), eventId);
            case "slash_search_page" -> {
                int target = Math.max(0, Integer.parseInt(String.valueOf(value.getOrDefault("page", "0"))));
                queueSearchCard(context, updateSearchPage(session, target), eventId);
            }
            case "slash_start_deal" -> startDraft(context, session, membershipId, false, eventId);
            case "slash_confirm_deal" -> confirm(context, session, eventId);
            case "slash_cancel" -> cancel(context, session, eventId);
            case "slash_confirm_owner" -> confirmOwnerTransfer(context, session, parseUuid(value.get("token")), eventId);
            default -> throw ApiException.badRequest("FEISHU_DEAL_CARD_ACTION_UNKNOWN", "卡片操作已失效，请重新执行命令");
        }
        return true;
    }

    private void search(Context context, EventMessage message, String query, String eventId) {
        String clean = query == null ? "" : query.trim();
        if (clean.isEmpty() || clean.length() > 100) {
            throw ApiException.badRequest("FEISHU_DEAL_SEARCH_INVALID", "请输入 1–100 个字的客户关键词");
        }
        Session session = upsertSearch(context, message, clean, 0);
        queueSearchCard(context, session, eventId);
    }

    private void selectByNumber(Context context, EventMessage message, String value, String eventId) {
        Session session = requireSession(context, message);
        int number;
        try {
            number = Integer.parseInt(value.trim());
        } catch (Exception exception) {
            throw ApiException.badRequest("FEISHU_DEAL_SELECTION_INVALID", "请输入当前页 1–5 的序号");
        }
        List<CustomerHit> page = currentHits(session);
        if (number < 1 || number > page.size()) {
            throw ApiException.badRequest("FEISHU_DEAL_SELECTION_INVALID", "当前页不存在该序号");
        }
        selectCustomer(context, session, page.get(number - 1).id(), eventId);
    }

    private void page(Context context, EventMessage message, int direction, String eventId) {
        Session session = requireSession(context, message);
        if (session.searchQuery() == null) {
            throw ApiException.conflict("FEISHU_DEAL_SEARCH_REQUIRED", "请先使用 /search 搜索客户");
        }
        queueSearchCard(context, updateSearchPage(session, Math.max(0, session.searchPage() + direction)), eventId);
    }

    private void startQuickCreate(Context context, EventMessage message, String arguments, String eventId) {
        if (!context.quickCustomerCreationEnabled()) {
            throw ApiException.conflict("FEISHU_QUICK_CREATE_DISABLED", "管理员尚未启用飞书快速建档");
        }
        String requested = arguments == null ? "" : arguments.trim();
        Person mentionedOwner = mentionedOwner(context, message);
        String name = requested;
        UUID ownerId = actors.require().membershipId();
        if (mentionedOwner != null) {
            ownerId = mentionedOwner.id();
            name = removeTrailingName(requested, mentionedOwner.name());
        }
        if (name.isBlank() || name.length() > 160) {
            throw ApiException.badRequest("FEISHU_QUICK_CUSTOMER_NAME_INVALID", "用法：/new 客户名 [@负责人]，姓名不得超过 160 字");
        }
        requireBoundPerson(context, ownerId);
        DuplicateSet duplicates = duplicates(name);
        Session session = upsertPendingNew(context, message, name, ownerId, duplicates.total());
        if (duplicates.total() > 0) {
            queueDuplicateCard(context, session, duplicates, eventId);
            return;
        }
        createQuickCustomer(context, session, null, eventId);
    }

    private void confirmQuickCreate(Context context, EventMessage message, String reason, String eventId) {
        Session session = requireSession(context, message);
        if (!"NEW_PENDING".equals(session.status()) || session.pendingCustomerName() == null) {
            throw ApiException.conflict("FEISHU_QUICK_CREATE_NOT_PENDING", "当前没有待确认的同名建档");
        }
        String clean = reason == null ? "" : reason.trim();
        if (clean.length() < 2 || clean.length() > 500) {
            throw ApiException.badRequest("FEISHU_QUICK_CREATE_REASON_REQUIRED", "请填写 2–500 字的继续新建原因");
        }
        createQuickCustomer(context, session, clean, eventId);
    }

    private void createQuickCustomer(Context context, Session session, String reason, String eventId) {
        CustomerDtos.Response created = customers.createFromFeishuBot(session.pendingCustomerName(),
                session.pendingOwnerMembershipId(), reason);
        int changed = jdbc.update("""
                update feishu_deal_bot_sessions set customer_id=?,created_customer_id=?,
                    customer_created_in_session=true,primary_membership_id=null,assistant_membership_ids='{}',
                    order_amount_cents=0,bonus_total_cents=?,allocations='{}'::jsonb,status='DRAFT',
                    edit_count=0,draft_ready_at=null,pending_customer_name=null,
                    pending_owner_membership_id=null,pending_duplicate_count=0,
                    expires_at=now()+interval '15 minutes',updated_at=now(),version=version+1
                where id=? and status='NEW_PENDING'
                """, created.id(), created.id(), context.defaultBonusCents(), session.id());
        if (changed != 1) {
            throw ApiException.conflict("FEISHU_QUICK_CREATE_CONFLICT", "快速建档会话已更新，请重新执行 /new");
        }
        Session updated = requireSession(context.integrationId(), session.chatId(), session.membershipId());
        queueText(context, session.chatId(), eventId + ":quick-created",
                "✅ 已创建正式客户「" + created.fullName() + "」，取消成单草稿也不会删除客户。");
        queueDraftCard(context, updated, eventId);
    }

    private void queueDuplicateCard(Context context, Session session, DuplicateSet duplicates, String eventId) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(markdown("发现 **" + duplicates.total() + "** 个同名档案。请优先复用；"
                + (duplicates.hidden() > 0 ? "其中 " + duplicates.hidden() + " 个当前不可见。\n" : "\n")
                + "确认是新客户时，使用 `/new-confirm 原因`。"));
        int index = 1;
        for (CustomerHit hit : duplicates.visible()) {
            elements.add(markdown(index++ + ". **" + safe(hit.name()) + "** · "
                    + safe(blank(hit.school(), "未填学校")) + " · 负责人 " + safe(blank(hit.owner(), "未知"))
                    + (hit.archived() ? " · 已归档" : "")));
            if (!hit.archived()) elements.add(button("复用该客户", "slash_select_customer",
                    Map.of("customerId", hit.id().toString())));
        }
        queueInteractive(context, session.chatId(), eventId + ":duplicates", card("同名客户核对", elements));
    }

    private void queueSearchCard(Context context, Session session, String eventId) {
        List<CustomerHit> all = searchHits(session.searchQuery());
        int from = Math.min(all.size(), session.searchPage() * 5);
        int to = Math.min(all.size(), from + 5);
        List<Map<String, Object>> elements = new ArrayList<>();
        if (from == to) elements.add(markdown("未找到可编辑客户。可使用 `/new 客户名`快速建档。"));
        for (int index = from; index < to; index++) {
            CustomerHit hit = all.get(index);
            elements.add(markdown((index - from + 1) + ". **" + safe(hit.name()) + "**\n"
                    + safe(blank(hit.school(), "未填学校")) + " · 负责人 " + safe(blank(hit.owner(), "未知"))
                    + " · 最近跟进 " + (hit.lastContactAt() == null ? "暂无" : TIME.format(hit.lastContactAt()))));
            elements.add(button("选择客户", "slash_select_customer", Map.of("customerId", hit.id().toString())));
            if (!workbenchBaseUrl.isBlank()) {
                elements.add(linkButton("查看完整档案", crmUrl("/customers/" + hit.id()), false));
                elements.add(linkButton("网页成单", dealUrl(hit.id()), true));
            }
        }
        if (session.searchPage() > 0) elements.add(button("上一页", "slash_search_page",
                Map.of("page", session.searchPage() - 1)));
        if (to < all.size()) elements.add(button("下一页", "slash_search_page",
                Map.of("page", session.searchPage() + 1)));
        queueInteractive(context, session.chatId(), eventId + ":search:" + session.searchPage(),
                card("客户搜索 · 第 " + (session.searchPage() + 1) + " 页", elements));
    }

    private List<CustomerHit> currentHits(Session session) {
        List<CustomerHit> hits = searchHits(session.searchQuery());
        int from = Math.min(hits.size(), session.searchPage() * 5);
        return hits.subList(from, Math.min(hits.size(), from + 5));
    }

    private List<CustomerHit> searchHits(String query) {
        if (query == null) return List.of();
        CurrentActor actor = actors.require();
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<CustomerHit> hits = new ArrayList<>();
        for (CustomerEntity customer : customers.visibleEntities(actor, false)) {
            try {
                customers.requireProfileEditable(customer, actor);
            } catch (ApiException ignored) {
                continue;
            }
            int score = matchScore(customer, needle);
            if (score >= 0) hits.add(hit(customer, score));
        }
        hits.sort(Comparator.comparingInt(CustomerHit::score).reversed()
                .thenComparing(CustomerHit::lastContactAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CustomerHit::name));
        return hits.size() > 100 ? hits.subList(0, 100) : hits;
    }

    private void selectCustomer(Context context, Session session, UUID customerId, String eventId) {
        CurrentActor actor = actors.require();
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        customers.requireProfileEditable(customer, actor);
        if (customer.getArchivedAt() != null) throw ApiException.conflict("CUSTOMER_ARCHIVED", "归档客户不能成单");
        jdbc.update("""
                update feishu_deal_bot_sessions set customer_id=?,created_customer_id=null,
                    customer_created_in_session=false,primary_membership_id=null,assistant_membership_ids='{}',
                    order_amount_cents=0,bonus_total_cents=0,allocations='{}'::jsonb,status='SELECTED',
                    edit_count=0,draft_ready_at=null,pending_customer_name=null,pending_owner_membership_id=null,
                    pending_duplicate_count=0,expires_at=now()+interval '15 minutes',updated_at=now(),
                    version=version+1 where id=?
                """, customerId, session.id());
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(markdown("已选择 **" + safe(customer.getFullName()) + "**\n"
                + safe(blank(customer.getSchool(), "未填学校")) + " · 负责人 "
                + safe(blank(customer.getOwnerDisplayName(), "未知"))));
        elements.add(button("发起成单", "slash_start_deal", Map.of()));
        elements.add(button("取消", "slash_cancel", Map.of()));
        queueInteractive(context, session.chatId(), eventId + ":selected", card("客户已选择", elements));
    }

    private void startDraft(Context context, Session session, UUID defaultPrimary, boolean quick, String eventId) {
        if (session.customerId() == null) throw ApiException.conflict("FEISHU_DEAL_CUSTOMER_REQUIRED", "请先搜索并选择客户");
        CustomerEntity customer = customers.requireVisibleEntity(session.customerId(), actors.require());
        customers.requireProfileEditable(customer, actors.require());
        if (customer.getArchivedAt() != null) throw ApiException.conflict("CUSTOMER_ARCHIVED", "归档客户不能成单");
        UUID primary = quick ? null : defaultPrimary;
        Map<String, Long> allocations = primary == null ? Map.of()
                : Map.of(primary.toString(), context.defaultBonusCents());
        jdbc.update("""
                update feishu_deal_bot_sessions set primary_membership_id=?,assistant_membership_ids='{}',
                    order_amount_cents=0,bonus_total_cents=?,allocations=cast(? as jsonb),status='DRAFT',
                    edit_count=0,draft_ready_at=null,expires_at=now()+interval '15 minutes',updated_at=now(),
                    version=version+1 where id=?
                """, primary, context.defaultBonusCents(), json(allocations), session.id());
        queueDraftCard(context, requireSession(context.integrationId(), session.chatId(), session.membershipId()), eventId);
    }

    private void modify(Context context, Session session, String type, String raw, String eventId) {
        requireDraft(session);
        if (!canRevise(session.draftReadyAt(), session.editCount())) {
            throw ApiException.conflict("FEISHU_DEAL_EDIT_LIMIT_REACHED", "已达到三次修订上限，请确认或取消");
        }
        Draft draft = Draft.from(session, allocations(session));
        if ("set".equals(type)) applyComposite(context, draft, raw);
        else applyOne(context, draft, type, raw);
        validateParticipants(draft);
        boolean nowReady = ready(draft);
        int editCount = nextEditCount(session.draftReadyAt(), session.editCount());
        jdbc.update("""
                update feishu_deal_bot_sessions set primary_membership_id=?,assistant_membership_ids=?,
                    order_amount_cents=?,bonus_total_cents=?,allocations=cast(? as jsonb),edit_count=?,
                    draft_ready_at=case when ? then coalesce(draft_ready_at,now()) else draft_ready_at end,
                    expires_at=now()+interval '15 minutes',updated_at=now(),version=version+1 where id=?
                """, draft.primary, draft.assistants.toArray(UUID[]::new), draft.orderAmount,
                draft.bonusTotal, stringifyAllocations(draft.allocations), editCount, nowReady, session.id());
        queueDraftCard(context, requireSession(context.integrationId(), session.chatId(), session.membershipId()), eventId);
    }

    private void applyComposite(Context context, Draft draft, String raw) {
        Map<String, String> values = parseSetArguments(raw);
        if (values.isEmpty()) throw ApiException.badRequest("FEISHU_DEAL_SET_INVALID", "用法：/set primary=姓名 assist=姓名1,姓名2 order=3000 bonus=500 split=姓名:500");
        for (String key : List.of("primary", "assist", "order", "bonus", "split")) {
            if (values.containsKey(key)) applyOne(context, draft, key, values.get(key));
        }
    }

    static Map<String, String> parseSetArguments(String raw) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        String[] tokens = raw.trim().split("\\s+");
        for (String token : tokens) {
            String[] pair = token.split("=", 2);
            if (pair.length != 2 || !Set.of("primary", "assist", "order", "bonus", "split").contains(pair[0])) {
                throw ApiException.badRequest("FEISHU_DEAL_SET_INVALID", "组合命令只支持 primary、assist、order、bonus、split");
            }
            if (result.put(pair[0], pair[1]) != null) {
                throw ApiException.badRequest("FEISHU_DEAL_SET_INVALID", "组合命令字段不能重复");
            }
        }
        return result;
    }

    static boolean canRevise(Instant firstCompletedAt, int editCount) {
        return firstCompletedAt == null || editCount < 3;
    }

    static int nextEditCount(Instant firstCompletedAt, int editCount) {
        return firstCompletedAt == null ? editCount : Math.addExact(editCount, 1);
    }

    private void applyOne(Context context, Draft draft, String type, String raw) {
        String value = raw == null ? "" : raw.trim();
        switch (type) {
            case "primary" -> {
                Person person = requirePerson(context, value);
                draft.primary = person.id();
                draft.assistants.remove(person.id());
                resetAllocations(draft);
            }
            case "assist" -> {
                if (draft.primary == null) throw ApiException.badRequest("DEAL_PRIMARY_REQUIRED", "请先选择主成单人");
                draft.assistants.clear();
                if (!value.equalsIgnoreCase("none") && !"无".equals(value)) {
                    for (String name : value.split("[\\s,，、]+")) {
                        if (!name.isBlank()) draft.assistants.add(requirePerson(context, name).id());
                    }
                }
                resetAllocations(draft);
            }
            case "order" -> draft.orderAmount = parsePositiveCents(value, "DEAL_ORDER_AMOUNT_INVALID", "订单金额");
            case "bonus" -> {
                draft.bonusTotal = parsePositiveCents(value, "DEAL_BONUS_INVALID", "奖金总额");
                resetAllocations(draft);
            }
            case "split" -> {
                if (draft.primary == null) throw ApiException.badRequest("DEAL_PRIMARY_REQUIRED", "请先选择主成单人");
                LinkedHashMap<UUID, Long> allocations = new LinkedHashMap<>();
                for (String item : value.split("[\\s,，]+")) {
                    String[] pair = item.split("[:=]", 2);
                    if (pair.length != 2) throw ApiException.badRequest("DEAL_ALLOCATION_INVALID", "分配格式应为：姓名=金额");
                    UUID memberId = requirePerson(context, pair[0]).id();
                    if (allocations.put(memberId, parseNonNegativeCents(pair[1])) != null) {
                        throw ApiException.badRequest("DEAL_PARTICIPANT_DUPLICATE", "同一人不能重复分配");
                    }
                }
                draft.allocations = allocations;
            }
            default -> throw ApiException.badRequest("FEISHU_DEAL_MODIFY_COMMAND_INVALID", "无法识别修改指令");
        }
    }

    private void requestOwnerTransfer(Context context, EventMessage message, String raw, String eventId) {
        Session session = requireSession(context, message);
        if (session.customerId() == null) throw ApiException.conflict("FEISHU_DEAL_CUSTOMER_REQUIRED", "请先选择客户");
        Person target = requirePerson(context, raw);
        CustomerEntity customer = customers.requireVisibleEntity(session.customerId(), actors.require());
        customers.requireProfileEditable(customer, actors.require());
        if (customer.getOwnerMembershipId().equals(target.id())) {
            throw ApiException.badRequest("INVALID_TRANSFER_TARGET", "接收人不能是当前主负责人");
        }
        jdbc.update("""
                update feishu_deal_bot_pending_actions set status='CANCELLED'
                where session_id=? and action_type='OWNER_TRANSFER' and status='PENDING'
                """, session.id());
        UUID token = UUID.randomUUID();
        jdbc.update("""
                insert into feishu_deal_bot_pending_actions
                (id,integration_id,organization_id,session_id,membership_id,action_type,customer_id,
                 target_membership_id,expected_customer_version,status,expires_at)
                values (?,?,?,?,?,'OWNER_TRANSFER',?,?,?,'PENDING',now()+interval '5 minutes')
                """, token, context.integrationId(), context.organizationId(), session.id(), session.membershipId(),
                customer.getId(), target.id(), customer.getVersion());
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(markdown("确认将客户 **" + safe(customer.getFullName()) + "** 从 **"
                + safe(blank(customer.getOwnerDisplayName(), "未知")) + "** 转接给 **" + safe(target.name())
                + "**？\n待办提醒和归属团队将同步调整，成单贡献人不变。"));
        elements.add(button("确认转接", "slash_confirm_owner", Map.of("token", token.toString())));
        elements.add(button("取消", "slash_cancel", Map.of()));
        queueInteractive(context, session.chatId(), eventId + ":owner-confirm", card("负责人转接确认", elements));
    }

    private void confirmOwnerTransfer(Context context, Session session, UUID token, String eventId) {
        PendingOwner pending = jdbc.query("""
                select id,customer_id,target_membership_id,expected_customer_version
                from feishu_deal_bot_pending_actions
                where id=? and integration_id=? and organization_id=? and session_id=? and membership_id=?
                  and action_type='OWNER_TRANSFER' and status='PENDING' and expires_at>now()
                for update
                """, (rs, row) -> new PendingOwner(rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getObject("target_membership_id", UUID.class),
                rs.getLong("expected_customer_version")), token, context.integrationId(), context.organizationId(),
                session.id(), session.membershipId()).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("FEISHU_OWNER_CONFIRMATION_EXPIRED", "转接确认已失效，请重新使用 /owner"));
        CustomerEntity before = customers.requireVisibleEntity(pending.customerId(), actors.require());
        UUID previousOwner = before.getOwnerMembershipId();
        String previousName = before.getOwnerDisplayName();
        CustomerDtos.Response updated = customers.transferOwnership(pending.customerId(),
                new CustomerDtos.TransferOwnershipRequest(pending.targetMembershipId(),
                        pending.expectedCustomerVersion(), "飞书成单机器人转接"));
        jdbc.update("update feishu_deal_bot_pending_actions set status='APPLIED',applied_at=now() where id=? and status='PENDING'",
                pending.id());
        notifications.publish(context.organizationId(), List.of(previousOwner, pending.targetMembershipId()),
                "CUSTOMER_OWNERSHIP_TRANSFERRED", "客户负责人已转接",
                "客户「" + updated.fullName() + "」已从" + blank(previousName, "原负责人") + "转接给"
                        + updated.ownerDisplayName(), "/customers/" + updated.id(),
                "feishu-owner-transfer:" + pending.id());
        queueText(context, session.chatId(), eventId + ":owner-applied",
                "✅ 负责人已更新为「" + updated.ownerDisplayName() + "」；主成单人和奖金草稿未改变。");
    }

    private void confirm(Context context, Session session, String eventId) {
        requireDraft(session);
        if (!ready(session)) {
            throw ApiException.badRequest("FEISHU_DEAL_DRAFT_INCOMPLETE",
                    "确认前必须设置主成单人、订单金额、奖金总额并完成分配");
        }
        UUID requestId = confirmations.confirmFromFeishu(new DealConfirmationService.FeishuConfirmation(
                session.customerId(), session.primaryMembershipId(), session.assistantMembershipIds(),
                session.orderAmountCents(), session.bonusTotalCents(), allocations(session), eventId));
        int changed = jdbc.update("""
                update feishu_deal_bot_sessions set status='CONFIRMED',confirmed_request_id=?,updated_at=now(),
                    version=version+1 where id=? and status='DRAFT'
                """, requestId, session.id());
        if (changed != 1) throw ApiException.conflict("FEISHU_DEAL_CONFIRMATION_CONFLICT", "成单草稿已变更，请重新查询");
        queueText(context, session.chatId(), eventId + ":confirmed", "✅ 成单已确认，订单金额和奖金已记录，全员广播已进入发送队列。");
    }

    private void cancel(Context context, Session session, String eventId) {
        jdbc.update("""
                update feishu_deal_bot_sessions set status='CANCELLED',updated_at=now(),version=version+1
                where id=? and status in ('SEARCHING','NEW_PENDING','SELECTED','DRAFT')
                """, session.id());
        String suffix = session.customerCreatedInSession()
                ? "已通过 /new 创建的正式客户将继续保留。" : "未修改客户档案。";
        queueText(context, session.chatId(), eventId + ":cancelled", "已取消当前会话。" + suffix);
    }

    private void queueDraftCard(Context context, Session session, String eventId) {
        CustomerEntity customer = customers.requireVisibleEntity(session.customerId(), actors.require());
        List<String> lines = new ArrayList<>();
        lines.add("**客户**：" + safe(customer.getFullName()));
        lines.add("**负责人**：" + safe(blank(customer.getOwnerDisplayName(), "未知")));
        lines.add("**主成单人**：" + (session.primaryMembershipId() == null ? "待选择" : safe(personName(session.primaryMembershipId()))));
        lines.add("**协助**：" + (session.assistantMembershipIds().isEmpty() ? "无" : session.assistantMembershipIds().stream()
                .map(this::personName).reduce((a, b) -> a + "、" + b).orElse("无")));
        lines.add("**订单金额**：" + (session.orderAmountCents() > 0 ? "¥" + formatCents(session.orderAmountCents()) : "待填写"));
        lines.add("**奖金总额**：" + (session.bonusTotalCents() > 0 ? "¥" + formatCents(session.bonusTotalCents()) : "待填写"));
        lines.add("**分配**：" + allocationText(session));
        lines.add("**状态**：" + (ready(session) ? "可确认 · 剩余修订 " + Math.max(0, 3 - session.editCount()) + " 次" : "待补全"));
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(markdown(String.join("\n", lines)));
        elements.add(button("确认成单", "slash_confirm_deal", Map.of()));
        elements.add(button("取消", "slash_cancel", Map.of()));
        queueInteractive(context, session.chatId(), eventId + ":draft:" + session.version(), card("成单草稿", elements));
    }

    private void queueCurrent(Context context, Session session, String eventId) {
        if ("DRAFT".equals(session.status())) queueDraftCard(context, session, eventId);
        else queueText(context, session.chatId(), eventId + ":current", "当前会话：" + session.status()
                + (session.customerId() == null ? "" : " · 客户 " + customerName(session.customerId()))
                + " · " + TIME.format(session.expiresAt()) + " 前有效。");
    }

    private void queueDetail(Context context, Session session, String eventId) {
        if (session.customerId() == null) throw ApiException.conflict("FEISHU_DEAL_CUSTOMER_REQUIRED", "请先选择客户");
        CustomerEntity customer = customers.requireVisibleEntity(session.customerId(), actors.require());
        queueText(context, session.chatId(), eventId + ":detail", "客户：" + customer.getFullName()
                + "\n学校：" + blank(customer.getSchool(), "未填") + "\n年级：" + blank(customer.getGrade(), "未填")
                + "\n负责人：" + blank(customer.getOwnerDisplayName(), "未知") + "\n最近跟进："
                + (customer.getLastContactAt() == null ? "暂无" : TIME.format(customer.getLastContactAt())));
    }

    private void queueMembers(Context context, EventMessage message, String query, String eventId) {
        String clean = query == null ? "" : query.trim();
        List<Person> people = eligibleBoundPeople(context, clean);
        StringBuilder text = new StringBuilder("可分配成员（仅已绑定且启用的客户维护成员）：\n");
        if (people.isEmpty()) text.append("未找到匹配成员");
        else people.stream().limit(20).forEach(person -> text.append("• ").append(person.name()).append("\n"));
        queueText(context, message.getChatId(), eventId + ":members", text.toString().trim());
    }

    private void queueMine(Context context, String rawMonth, UUID membershipId, String eventId) {
        YearMonth month = parseMonth(rawMonth);
        Instant from = month.atDay(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay(BUSINESS_ZONE).toInstant();
        Map<String, Object> primary = jdbc.queryForMap("""
                select count(*) deal_count,coalesce(sum(request.order_amount_cents),0) order_total
                from customer_deal_participants participant
                join customer_deal_requests request on request.id=participant.request_id and request.status='APPROVED'
                where participant.organization_id=? and participant.membership_id=?
                  and participant.participant_role='PRIMARY' and request.confirmed_at>=? and request.confirmed_at<?
                """, context.organizationId(), membershipId, Timestamp.from(from), Timestamp.from(to));
        Long bonus = jdbc.queryForObject("""
                select coalesce(sum(ledger.amount_cents),0) from customer_deal_bonus_ledger ledger
                join customer_deal_requests request on request.id=ledger.request_id and request.status='APPROVED'
                where ledger.organization_id=? and ledger.membership_id=?
                  and request.confirmed_at>=? and request.confirmed_at<?
                """, Long.class, context.organizationId(), membershipId, Timestamp.from(from), Timestamp.from(to));
        queueText(context, context.submissionChatId(), eventId + ":mine", month + " 我的成单\n主成单："
                + ((Number) primary.get("deal_count")).longValue() + " 单\n订单金额：¥"
                + formatCents(((Number) primary.get("order_total")).longValue()) + "\n个人奖金：¥"
                + formatCents(bonus == null ? 0 : bonus));
    }

    private void queueLeaderboard(Context context, String rawMonth, String eventId) {
        YearMonth month = parseMonth(rawMonth);
        FeishuDealBotDtos.LeaderboardResponse board = confirmations.leaderboardFor(context.organizationId(), month.atDay(1));
        queueText(context, context.submissionChatId(), eventId + ":leaderboard:" + month, board.previewText());
    }

    private void queueStatus(Context context, EventMessage message, String eventId) {
        Session session = activeSession(context.integrationId(), message.getChatId(), actors.require().membershipId());
        queueText(context, message.getChatId(), eventId + ":status", "成单机器人：已启用\n快速建档："
                + (context.quickCustomerCreationEnabled() ? "已启用" : "未启用") + "\n账号绑定：正常\n当前会话："
                + (session == null ? "无" : session.status() + "（" + TIME.format(session.expiresAt()) + " 到期）"));
    }

    private void queueHelp(Context context, String eventId) {
        String help = """
                **网页入口**
                `/crm` 打开完整 CRM · `/deal-web` 打开快捷成单页面

                **客户**
                `/search 关键词` 搜索 · `/select 1` 选择 · `/next` `/prev` 翻页
                `/new 客户名 [@负责人]` 快速建档 · `/new-confirm 原因` 同名继续新建
                `/detail` 客户摘要 · `/members 关键词` 可分配成员 · `/owner @成员` 转接负责人

                **成单**
                `/deal` 发起 · `/primary @成员` 主成单人 · `/assist @成员1,@成员2` 协助
                `/order 3000` 订单金额 · `/bonus 500` 奖金总额
                `/split @张三=300 @李四=200` 奖金分配
                `/set primary=张三 assist=李四 order=3000 bonus=500 split=张三:300,李四:200` 一次设置
                `/current` 当前草稿 · `/confirm` 确认 · `/cancel` 取消

                **统计**
                `/mine [YYYY-MM]` 我的成单 · `/leaderboard [YYYY-MM]` 月榜 · `/status` 运行状态

                订单金额和奖金均必须大于 0；快速建档后即使取消成单，客户也会保留。
                """;
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(markdown(help));
        addEntryButtons(elements);
        queueInteractive(context, context.submissionChatId(), eventId + ":help",
                card("英格云成单机器人教程", elements));
    }

    private void queueEntryCard(Context context, String eventId, boolean deal) {
        if (workbenchBaseUrl.isBlank()) {
            throw ApiException.conflict("FEISHU_WEB_ENTRY_NOT_CONFIGURED", "管理员尚未配置飞书网页入口");
        }
        List<Map<String, Object>> elements = new ArrayList<>();
        if (deal) {
            elements.add(markdown("独立快捷成单页面：搜索或新建客户、转接负责人、设置成单人员与奖金，并完成广播。"));
            elements.add(linkButton("打开快捷成单", dealUrl(null), true));
        } else {
            elements.add(markdown("打开英格云完整 CRM，可继续使用客户、时间线、提醒、任务、日报和公共池等功能。"));
            elements.add(linkButton("打开完整 CRM", crmUrl("/dashboard"), true));
        }
        queueInteractive(context, context.submissionChatId(), eventId + (deal ? ":deal-web" : ":crm"),
                card(deal ? "快捷成单" : "完整 CRM", elements));
    }

    private void addEntryButtons(List<Map<String, Object>> elements) {
        if (workbenchBaseUrl.isBlank()) return;
        elements.add(linkButton("进入完整 CRM", crmUrl("/dashboard"), false));
        elements.add(linkButton("打开快捷成单", dealUrl(null), true));
    }

    private Session upsertSearch(Context context, EventMessage message, String query, int page) {
        expire(context.integrationId(), message.getChatId(), actors.require().membershipId());
        UUID membershipId = actors.require().membershipId();
        int changed = jdbc.update("""
                update feishu_deal_bot_sessions set customer_id=null,created_customer_id=null,
                    customer_created_in_session=false,primary_membership_id=null,assistant_membership_ids='{}',
                    order_amount_cents=0,bonus_total_cents=0,allocations='{}'::jsonb,search_query=?,search_page=?,
                    status='SEARCHING',edit_count=0,draft_ready_at=null,last_message_id=?,
                    pending_customer_name=null,pending_owner_membership_id=null,pending_duplicate_count=0,
                    expires_at=now()+interval '15 minutes',updated_at=now(),version=version+1
                where integration_id=? and chat_id=? and membership_id=?
                  and status in ('SEARCHING','NEW_PENDING','SELECTED','DRAFT')
                """, query, page, message.getMessageId(), context.integrationId(), message.getChatId(), membershipId);
        if (changed == 0) insertSession(context, message, membershipId, "SEARCHING", query, page, null, null, 0);
        return requireSession(context.integrationId(), message.getChatId(), membershipId);
    }

    private Session upsertPendingNew(Context context, EventMessage message, String name, UUID owner, int duplicateCount) {
        expire(context.integrationId(), message.getChatId(), actors.require().membershipId());
        UUID membershipId = actors.require().membershipId();
        int changed = jdbc.update("""
                update feishu_deal_bot_sessions set customer_id=null,created_customer_id=null,
                    customer_created_in_session=false,primary_membership_id=null,assistant_membership_ids='{}',
                    order_amount_cents=0,bonus_total_cents=0,allocations='{}'::jsonb,search_query=null,search_page=0,
                    status='NEW_PENDING',edit_count=0,draft_ready_at=null,last_message_id=?,pending_customer_name=?,
                    pending_owner_membership_id=?,pending_duplicate_count=?,expires_at=now()+interval '15 minutes',
                    updated_at=now(),version=version+1 where integration_id=? and chat_id=? and membership_id=?
                      and status in ('SEARCHING','NEW_PENDING','SELECTED','DRAFT')
                """, message.getMessageId(), name, owner, duplicateCount, context.integrationId(),
                message.getChatId(), membershipId);
        if (changed == 0) insertSession(context, message, membershipId, "NEW_PENDING", null, 0, name, owner, duplicateCount);
        return requireSession(context.integrationId(), message.getChatId(), membershipId);
    }

    private void insertSession(Context context, EventMessage message, UUID membershipId, String status,
                               String searchQuery, int page, String pendingName, UUID pendingOwner, int duplicates) {
        try {
            jdbc.update("""
                    insert into feishu_deal_bot_sessions
                    (id,integration_id,organization_id,chat_id,feishu_user_id,membership_id,search_query,
                     search_page,status,last_message_id,pending_customer_name,pending_owner_membership_id,
                     pending_duplicate_count,expires_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,now()+interval '15 minutes')
                    """, UUID.randomUUID(), context.integrationId(), context.organizationId(), message.getChatId(),
                    context.feishuUserId(), membershipId, searchQuery, page, status, message.getMessageId(),
                    pendingName, pendingOwner, duplicates);
        } catch (DuplicateKeyException duplicate) {
            throw ApiException.conflict("FEISHU_DEAL_SESSION_CONFLICT", "当前会话已更新，请重试");
        }
    }

    private Session updateSearchPage(Session session, int page) {
        jdbc.update("""
                update feishu_deal_bot_sessions set search_page=?,status='SEARCHING',
                    expires_at=now()+interval '15 minutes',updated_at=now(),version=version+1 where id=?
                """, page, session.id());
        return requireSession(session.integrationId(), session.chatId(), session.membershipId());
    }

    private Session requireSession(Context context, EventMessage message) {
        return requireSession(context.integrationId(), message.getChatId(), actors.require().membershipId());
    }

    private Session requireSession(UUID integrationId, String chatId, UUID membershipId) {
        Session session = activeSession(integrationId, chatId, membershipId);
        if (session == null) throw ApiException.conflict("FEISHU_DEAL_SESSION_NOT_FOUND", "当前没有可用会话，请先使用 /search 或 /new");
        return session;
    }

    private Session activeSession(UUID integrationId, String chatId, UUID membershipId) {
        expire(integrationId, chatId, membershipId);
        return jdbc.query("""
                select * from feishu_deal_bot_sessions where integration_id=? and chat_id=? and membership_id=?
                  and status in ('SEARCHING','NEW_PENDING','SELECTED','DRAFT') and expires_at>now()
                for update
                """, this::mapSession, integrationId, chatId, membershipId).stream().findFirst().orElse(null);
    }

    private void expire(UUID integrationId, String chatId, UUID membershipId) {
        jdbc.update("""
                update feishu_deal_bot_sessions set status='EXPIRED',updated_at=now()
                where integration_id=? and chat_id=? and membership_id=?
                  and status in ('SEARCHING','NEW_PENDING','SELECTED','DRAFT') and expires_at<=now()
                """, integrationId, chatId, membershipId);
        jdbc.update("""
                update feishu_deal_bot_pending_actions set status='EXPIRED'
                where integration_id=? and membership_id=? and status='PENDING' and expires_at<=now()
                """, integrationId, membershipId);
    }

    private Session mapSession(ResultSet rs, int row) throws SQLException {
        Array array = rs.getArray("assistant_membership_ids");
        List<UUID> assistants = array == null ? List.of() : Arrays.asList((UUID[]) array.getArray());
        Timestamp ready = rs.getTimestamp("draft_ready_at");
        return new Session(rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("chat_id"), rs.getString("feishu_user_id"),
                rs.getObject("membership_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("primary_membership_id", UUID.class), assistants, rs.getLong("order_amount_cents"),
                rs.getLong("bonus_total_cents"), rs.getString("allocations"), rs.getString("search_query"),
                rs.getInt("search_page"), rs.getString("status"), rs.getInt("edit_count"),
                ready == null ? null : ready.toInstant(), rs.getObject("created_customer_id", UUID.class),
                rs.getBoolean("customer_created_in_session"), rs.getString("pending_customer_name"),
                rs.getObject("pending_owner_membership_id", UUID.class), rs.getInt("pending_duplicate_count"),
                rs.getTimestamp("expires_at").toInstant(), rs.getLong("version"));
    }

    private DuplicateSet duplicates(String name) {
        CurrentActor actor = actors.require();
        List<CustomerHit> visible = customers.visibleEntities(actor, true).stream()
                .filter(customer -> customer.getFullName() != null && customer.getFullName().trim().equalsIgnoreCase(name.trim()))
                .map(customer -> hit(customer, 100)).limit(5).toList();
        Long total = jdbc.queryForObject("""
                select count(*) from customers where organization_id=? and lower(btrim(full_name))=lower(btrim(?))
                """, Long.class, actor.organizationId(), name);
        int count = total == null ? 0 : Math.toIntExact(total);
        return new DuplicateSet(count, Math.max(0, count - visible.size()), visible);
    }

    private List<Person> eligibleBoundPeople(Context context, String query) {
        String pattern = "%" + (query == null ? "" : query.toLowerCase(Locale.ROOT)) + "%";
        return jdbc.query("""
                select distinct membership.id,account.display_name
                from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                join feishu_identity_bindings binding on binding.membership_id=membership.id
                  and binding.integration_id=? and binding.active=true
                where membership.organization_id=? and membership.status='ACTIVE'
                  and lower(account.display_name) like ?
                  and exists (select 1 from membership_roles assignment join roles role on role.id=assignment.role_id
                              where assignment.membership_id=membership.id
                                and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER'))
                order by account.display_name,membership.id
                """, (rs, row) -> new Person(rs.getObject("id", UUID.class), rs.getString("display_name")),
                context.integrationId(), context.organizationId(), pattern);
    }

    private Person requirePerson(Context context, String raw) {
        String clean = raw == null ? "" : raw.replace("@", "").trim();
        List<Person> matches = eligibleBoundPeople(context, clean).stream()
                .filter(person -> person.name().equalsIgnoreCase(clean)).toList();
        if (matches.size() != 1) throw ApiException.badRequest("FEISHU_DEAL_PERSON_AMBIGUOUS",
                "成员未绑定、已停用或姓名重复：" + clean + "；可使用 /members 查询");
        return matches.getFirst();
    }

    private void requireBoundPerson(Context context, UUID membershipId) {
        if (eligibleBoundPeople(context, "").stream().noneMatch(person -> person.id().equals(membershipId))) {
            throw ApiException.badRequest("FEISHU_DEAL_PERSON_INELIGIBLE", "负责人必须已绑定飞书且具备客户维护权限");
        }
    }

    private Person mentionedOwner(Context context, EventMessage message) {
        if (message.getMentions() == null) return null;
        LinkedHashMap<UUID, Person> matched = new LinkedHashMap<>();
        int nonBotMentionCount = 0;
        for (MentionEvent mention : message.getMentions()) {
            if (mention == null || mention.getId() == null) continue;
            if (context.botOpenId() != null && context.botOpenId().equals(mention.getId().getOpenId())) continue;
            nonBotMentionCount++;
            String userId = firstNonBlank(mention.getId().getUserId(), mention.getId().getOpenId(), mention.getId().getUnionId());
            if (userId == null) continue;
            List<Person> result = jdbc.query("""
                    select membership.id,account.display_name from feishu_identity_bindings binding
                    join memberships membership on membership.id=binding.membership_id and membership.status='ACTIVE'
                    join user_accounts account on account.id=membership.user_id and account.enabled=true
                    where binding.integration_id=? and binding.active=true and membership.organization_id=?
                      and (binding.feishu_user_id=? or binding.open_id=? or binding.union_id=?)
                    """, (rs, row) -> new Person(rs.getObject("id", UUID.class), rs.getString("display_name")),
                    context.integrationId(), context.organizationId(), mention.getId().getUserId(),
                    mention.getId().getOpenId(), mention.getId().getUnionId());
            if (result.size() == 1) matched.put(result.getFirst().id(), result.getFirst());
        }
        if (nonBotMentionCount > 1) {
            throw ApiException.badRequest("FEISHU_QUICK_OWNER_AMBIGUOUS", "/new 一次只能指定一名负责人");
        }
        if (nonBotMentionCount == 1 && matched.isEmpty()) {
            throw ApiException.badRequest("FEISHU_DEAL_PERSON_INELIGIBLE", "被提及的负责人未绑定、已停用或无客户维护权限");
        }
        return matched.values().stream().findFirst().orElse(null);
    }

    private LinkedHashMap<UUID, Long> allocations(Session session) {
        try {
            Map<String, Long> source = json.readValue(session.allocations(), new TypeReference<>() {});
            LinkedHashMap<UUID, Long> result = new LinkedHashMap<>();
            source.forEach((key, value) -> result.put(UUID.fromString(key), value));
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid stored deal allocation", exception);
        }
    }

    private void resetAllocations(Draft draft) {
        draft.allocations = new LinkedHashMap<>();
        if (draft.primary != null) {
            draft.allocations.put(draft.primary, draft.bonusTotal);
            draft.assistants.forEach(id -> draft.allocations.put(id, 0L));
        }
    }

    private void validateParticipants(Draft draft) {
        if (draft.assistants.size() > 2) throw ApiException.badRequest("DEAL_ASSISTANT_LIMIT", "协助人最多两名");
        Set<UUID> unique = new LinkedHashSet<>(draft.assistants);
        if (unique.size() != draft.assistants.size() || draft.primary != null && unique.contains(draft.primary)) {
            throw ApiException.badRequest("DEAL_PARTICIPANT_DUPLICATE", "主成单人和协助人不能重复");
        }
    }

    private boolean ready(Session session) {
        return ready(Draft.from(session, allocations(session)));
    }

    private boolean ready(Draft draft) {
        if (draft.primary == null || draft.orderAmount <= 0 || draft.bonusTotal <= 0) return false;
        LinkedHashSet<UUID> expected = new LinkedHashSet<>();
        expected.add(draft.primary);
        expected.addAll(draft.assistants);
        if (!draft.allocations.keySet().equals(expected)) return false;
        try {
            return draft.allocations.values().stream().allMatch(value -> value != null && value >= 0)
                    && draft.allocations.values().stream().reduce(0L, Math::addExact) == draft.bonusTotal;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private void requireDraft(Session session) {
        if (!"DRAFT".equals(session.status())) throw ApiException.conflict("FEISHU_DEAL_DRAFT_REQUIRED", "请先使用 /deal 发起成单草稿");
    }

    private String allocationText(Session session) {
        LinkedHashMap<UUID, Long> values = allocations(session);
        if (values.isEmpty()) return "待分配";
        return values.entrySet().stream().map(entry -> personName(entry.getKey()) + "=¥" + formatCents(entry.getValue()))
                .reduce((left, right) -> left + "；" + right).orElse("待分配");
    }

    private String personName(UUID membershipId) {
        if (membershipId == null) return "待选择";
        return jdbc.query("""
                select account.display_name from memberships membership
                join user_accounts account on account.id=membership.user_id where membership.id=?
                """, (rs, row) -> rs.getString("display_name"), membershipId).stream().findFirst().orElse("未知成员");
    }

    private String customerName(UUID customerId) {
        return jdbc.query("select full_name from customers where id=?", (rs, row) -> rs.getString(1), customerId)
                .stream().findFirst().orElse("未知客户");
    }

    private CustomerHit hit(CustomerEntity customer, int score) {
        return new CustomerHit(customer.getId(), customer.getFullName(), customer.getSchool(),
                customer.getOwnerDisplayName(), customer.getLastContactAt(), customer.getArchivedAt() != null, score);
    }

    private int matchScore(CustomerEntity customer, String needle) {
        if (equals(customer.getFullName(), needle) || equals(customer.getNickname(), needle)) return 100;
        if (starts(customer.getFullName(), needle) || starts(customer.getNickname(), needle)) return 80;
        if (needle.matches("\\d{4}") && digits(customer.getPhone()).endsWith(needle)) return 75;
        if (contains(customer.getWechatId(), needle) || contains(customer.getQqId(), needle)) return 70;
        if (contains(customer.getFullName(), needle) || contains(customer.getNickname(), needle)) return 60;
        if (contains(customer.getSchool(), needle)) return 40;
        return -1;
    }

    private long parsePositiveCents(String value, String code, String label) {
        long cents = parseNonNegativeCents(value);
        if (cents <= 0) throw ApiException.badRequest(code, label + "必须大于 0 且最多两位小数");
        return cents;
    }

    private long parseNonNegativeCents(String value) {
        try {
            BigDecimal amount = new BigDecimal(value.replace("¥", "").replace("元", "").trim());
            if (amount.signum() < 0 || amount.scale() > 2) throw new NumberFormatException();
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (Exception exception) {
            throw ApiException.badRequest("DEAL_AMOUNT_INVALID", "金额必须是非负数且最多两位小数");
        }
    }

    private YearMonth parseMonth(String value) {
        if (value == null || value.isBlank()) return YearMonth.now(BUSINESS_ZONE);
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw ApiException.badRequest("DEAL_MONTH_INVALID", "月份格式应为 YYYY-MM");
        }
    }

    private void queueText(Context context, String chatId, String key, String text) {
        queue(context, chatId, "text", Map.of("text", text), key);
    }

    private void queueInteractive(Context context, String chatId, String key, Map<String, Object> payload) {
        queue(context, chatId, "interactive", payload, key);
    }

    private void queue(Context context, String chatId, String type, Map<String, Object> payload, String key) {
        UUID messageUuid = UUID.nameUUIDFromBytes((context.integrationId() + ":slash:" + key)
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                insert into feishu_deal_message_outbox
                (id,integration_id,organization_id,chat_id,message_type,payload,message_uuid,purpose)
                values (?,?,?,?,?,cast(? as jsonb),?,'BOT_REPLY')
                on conflict (integration_id,message_uuid) do nothing
                """, UUID.randomUUID(), context.integrationId(), context.organizationId(), chatId, type,
                json(payload), messageUuid);
    }

    private Map<String, Object> card(String title, List<Map<String, Object>> elements) {
        return Map.of("schema", "2.0", "config", Map.of("wide_screen_mode", true),
                "header", Map.of("title", Map.of("tag", "plain_text", "content", title), "template", "blue"),
                "body", Map.of("elements", elements));
    }

    private Map<String, Object> markdown(String content) {
        return Map.of("tag", "markdown", "content", content);
    }

    private Map<String, Object> button(String label, String action, Map<String, Object> extras) {
        Map<String, Object> value = new LinkedHashMap<>(extras);
        value.put("action", action);
        return Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", label),
                "type", action.contains("confirm") || action.contains("select") ? "primary" : "default",
                "value", value);
    }

    private Map<String, Object> linkButton(String label, String url, boolean primary) {
        return Map.of("tag", "button", "text", Map.of("tag", "plain_text", "content", label),
                "type", primary ? "primary" : "default", "url", url);
    }

    private String crmUrl(String returnTo) {
        return workbenchBaseUrl + "/feishu/crm?returnTo="
                + java.net.URLEncoder.encode(returnTo, StandardCharsets.UTF_8);
    }

    private String dealUrl(UUID customerId) {
        return workbenchBaseUrl + "/feishu/deals"
                + (customerId == null ? "" : "?customerId=" + customerId);
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().replaceAll("/+$", "");
    }

    private String stringifyAllocations(Map<UUID, Long> allocations) {
        LinkedHashMap<String, Long> value = new LinkedHashMap<>();
        allocations.forEach((id, cents) -> value.put(id.toString(), cents));
        return json(value);
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String normalizeCommand(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private String commandVerb(String command) {
        int space = command.indexOf(' ');
        return (space < 0 ? command : command.substring(0, space)).toLowerCase(Locale.ROOT);
    }

    private String commandArgs(String command) {
        int space = command.indexOf(' ');
        return space < 0 ? "" : command.substring(space + 1).trim();
    }

    private String removeTrailingName(String value, String name) {
        String clean = value == null ? "" : value.trim();
        String suffix = name == null ? "" : name.trim();
        if (!suffix.isEmpty() && clean.endsWith(suffix)) return clean.substring(0, clean.length() - suffix.length()).trim();
        return clean;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }

    private UUID parseUuid(Object value) {
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (Exception exception) {
            throw ApiException.badRequest("FEISHU_DEAL_CARD_INVALID", "卡片已失效，请重新操作");
        }
    }

    private boolean equals(String value, String needle) { return value != null && value.trim().equalsIgnoreCase(needle); }
    private boolean starts(String value, String needle) { return value != null && value.toLowerCase(Locale.ROOT).startsWith(needle); }
    private boolean contains(String value, String needle) { return value != null && value.toLowerCase(Locale.ROOT).contains(needle); }
    private String digits(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }
    private String safe(String value) { return value == null ? "" : value.replace("<", "＜").replace(">", "＞"); }
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String formatCents(long cents) { return BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString(); }

    public record Context(UUID integrationId, UUID organizationId, String submissionChatId, String botOpenId,
                          String feishuUserId, long defaultBonusCents, boolean quickCustomerCreationEnabled) {}

    private record Person(UUID id, String name) {}
    private record CustomerHit(UUID id, String name, String school, String owner, Instant lastContactAt,
                               boolean archived, int score) {}
    private record DuplicateSet(int total, int hidden, List<CustomerHit> visible) {}
    private record PendingOwner(UUID id, UUID customerId, UUID targetMembershipId, long expectedCustomerVersion) {}
    private record Session(UUID id, UUID integrationId, UUID organizationId, String chatId, String feishuUserId,
                           UUID membershipId, UUID customerId, UUID primaryMembershipId,
                           List<UUID> assistantMembershipIds, long orderAmountCents, long bonusTotalCents,
                           String allocations, String searchQuery, int searchPage, String status, int editCount,
                           Instant draftReadyAt, UUID createdCustomerId, boolean customerCreatedInSession,
                           String pendingCustomerName, UUID pendingOwnerMembershipId, int pendingDuplicateCount,
                           Instant expiresAt, long version) {}

    private static final class Draft {
        private UUID primary;
        private final List<UUID> assistants;
        private long orderAmount;
        private long bonusTotal;
        private LinkedHashMap<UUID, Long> allocations;

        private Draft(UUID primary, List<UUID> assistants, long orderAmount, long bonusTotal,
                      LinkedHashMap<UUID, Long> allocations) {
            this.primary = primary;
            this.assistants = assistants;
            this.orderAmount = orderAmount;
            this.bonusTotal = bonusTotal;
            this.allocations = allocations;
        }

        private static Draft from(Session session, LinkedHashMap<UUID, Long> allocations) {
            return new Draft(session.primaryMembershipId(), new ArrayList<>(session.assistantMembershipIds()),
                    session.orderAmountCents(), session.bonusTotalCents(), new LinkedHashMap<>(allocations));
        }
    }
}
