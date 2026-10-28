package com.yingge.cloud.feishu.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.oapi.event.cardcallback.model.CallBackAction;
import com.lark.oapi.event.cardcallback.model.CallBackOperator;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.event.cardcallback.model.CallBackToast;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.EventSender;
import com.lark.oapi.service.im.v1.model.MentionEvent;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.im.v1.model.UserId;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.deal.application.DealConfirmationService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class FeishuDealBotEventService {
    private static final Duration SESSION_TTL = Duration.ofMinutes(15);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M-d HH:mm")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FeishuActorExecutor actorExecutor;
    private final CurrentActorProvider actors;
    private final CustomerService customers;
    private final DealConfirmationService confirmations;
    private final FeishuDealBotSlashService slash;
    private final TransactionTemplate workTransactions;

    public FeishuDealBotEventService(JdbcTemplate jdbc, ObjectMapper json, FeishuActorExecutor actorExecutor,
                                     CurrentActorProvider actors, CustomerService customers,
                                     DealConfirmationService confirmations, FeishuDealBotSlashService slash,
                                     PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.json = json;
        this.actorExecutor = actorExecutor;
        this.actors = actors;
        this.customers = customers;
        this.confirmations = confirmations;
        this.slash = slash;
        this.workTransactions = new TransactionTemplate(transactionManager);
    }

    public void receiveMessage(UUID integrationId, P2MessageReceiveV1 envelope) {
        if (envelope == null || envelope.getHeader() == null || envelope.getEvent() == null) return;
        EventSender sender = envelope.getEvent().getSender();
        EventMessage message = envelope.getEvent().getMessage();
        if (sender == null || sender.getSenderId() == null || message == null || message.getMessageId() == null) return;
        Setting setting = setting(integrationId);
        if (setting == null || !setting.enabled()) return;
        String eventId = envelope.getHeader().getEventId();
        UUID inboxId = remember(integrationId, setting.organizationId(), eventId, message.getMessageId(),
                envelope.getHeader().getEventType(), message.getChatId(), sender.getSenderId().getUserId());
        if (inboxId == null) return;
        if (!setting.submissionChatId().equals(message.getChatId()) || "bot".equalsIgnoreCase(sender.getSenderType())) {
            finish(inboxId, "IGNORED", null);
            return;
        }
        Identity identity = identity(setting, sender.getTenantKey(), envelope.getHeader().getTenantKey(),
                sender.getSenderId());
        if (identity == null) {
            queueText(setting, message.getChatId(), eventId + ":unbound",
                    "请先在英格云设置中绑定当前飞书账号，绑定后再重试。");
            finish(inboxId, "REJECTED", "FEISHU_USER_NOT_BOUND");
            return;
        }
        if (!"text".equals(message.getMessageType())) {
            finish(inboxId, "IGNORED", "FEISHU_DEAL_TEXT_REQUIRED");
            return;
        }
        try {
            String text = commandText(message, setting.botOpenId());
            boolean mentioned = mentionsBot(message.getMentions(), setting.botOpenId());
            boolean hasSession = activeSession(integrationId, message.getChatId(), identity.membershipId()) != null;
            if (text.startsWith("/") && !mentioned) {
                finish(inboxId, "IGNORED", null);
                return;
            }
            if (!mentioned && !hasSession) {
                finish(inboxId, "IGNORED", null);
                return;
            }
            workTransactions.executeWithoutResult(ignored -> actorExecutor.as(identity.membershipId(), () -> {
                    processText(setting, identity, message, text, eventId);
                    return null;
                }));
            finish(inboxId, "APPLIED", null);
        } catch (ApiException exception) {
            queueText(setting, message.getChatId(), eventId + ":business-error",
                    exception.getMessage() + "\n追踪号：" + inboxId);
            finish(inboxId, "REJECTED", exception.code());
        } catch (RuntimeException exception) {
            queueText(setting, message.getChatId(), eventId + ":system-error",
                    "操作暂时失败，请稍后重试。\n追踪号：" + inboxId);
            finish(inboxId, "FAILED", "FEISHU_DEAL_PROCESSING_FAILED");
        }
    }

    public P2CardActionTriggerResponse receiveCard(UUID integrationId, P2CardActionTrigger envelope) {
        P2CardActionTriggerResponse response = new P2CardActionTriggerResponse();
        CallBackToast toast = new CallBackToast();
        toast.setType("success");
        toast.setContent("已处理");
        response.setToast(toast);
        if (envelope == null || envelope.getHeader() == null || envelope.getEvent() == null) return response;
        CallBackOperator operator = envelope.getEvent().getOperator();
        CallBackAction action = envelope.getEvent().getAction();
        String chatId = envelope.getEvent().getContext() == null ? null : envelope.getEvent().getContext().getOpenChatId();
        Setting setting = setting(integrationId);
        if (setting == null || !setting.enabled() || chatId == null || !chatId.equals(setting.submissionChatId())) return response;
        String eventId = envelope.getHeader().getEventId();
        UUID inboxId = remember(integrationId, setting.organizationId(), eventId,
                envelope.getEvent().getContext() == null ? null : envelope.getEvent().getContext().getOpenMessageId(),
                envelope.getHeader().getEventType(), chatId, operator == null ? null : operator.getUserId());
        if (inboxId == null) return response;
        Identity identity = operator == null ? null : identity(setting, operator.getTenantKey(),
                envelope.getHeader().getTenantKey(), operator.getUserId(), operator.getOpenId(),
                operator.getUnionId());
        if (identity == null) {
            toast.setType("error");
            toast.setContent("请先绑定英格云账号");
            finish(inboxId, "REJECTED", "FEISHU_USER_NOT_BOUND");
            return response;
        }
        try {
            Map<String, Object> value = action == null || action.getValue() == null ? Map.of() : action.getValue();
            workTransactions.executeWithoutResult(ignored -> actorExecutor.as(identity.membershipId(), () -> {
                    processCard(setting, identity, chatId, value, eventId);
                    return null;
                }));
            finish(inboxId, "APPLIED", null);
        } catch (ApiException exception) {
            toast.setType("error");
            toast.setContent(exception.getMessage());
            finish(inboxId, "REJECTED", exception.code());
        } catch (RuntimeException exception) {
            toast.setType("error");
            toast.setContent("操作暂时失败");
            finish(inboxId, "FAILED", "FEISHU_DEAL_CARD_FAILED");
        }
        return response;
    }

    private void processText(Setting setting, Identity identity, EventMessage message, String raw, String eventId) {
        CurrentActor actor = actors.require();
        if (!actor.canWriteCustomers()) throw ApiException.forbidden("当前账号无客户维护权限");
        String command = raw == null ? "" : raw.trim();
        String slashAlias = legacySlash(command);
        if (slashAlias != null) {
            slash.process(slashContext(setting, identity), message, slashAlias, eventId);
            return;
        }
        if (command.startsWith("/")) {
            slash.process(slashContext(setting, identity), message, command, eventId);
            return;
        }
        Session session = requireSession(setting.integrationId(), message.getChatId(), identity.membershipId());
        if ("下一页".equals(command) || "next".equalsIgnoreCase(command)) {
            Session updated = updateSearchPage(session, session.searchPage() + 1);
            queueSearchCard(setting, updated, eventId);
        } else if ("上一页".equals(command) || "prev".equalsIgnoreCase(command)) {
            Session updated = updateSearchPage(session, Math.max(0, session.searchPage() - 1));
            queueSearchCard(setting, updated, eventId);
        } else if ("成单".equals(command)) {
            startDraft(setting, session, actor, eventId);
        } else if (command.startsWith("修改 ")) {
            modifyDraft(setting, session, message, command.substring(3).trim(), eventId);
        } else if ("确认".equals(command)) {
            confirm(setting, session, eventId);
        } else if ("取消".equals(command)) {
            cancel(setting, session, eventId);
        } else {
            queueText(setting, message.getChatId(), eventId + ":help",
                    "可用指令：/search 关键词、成单、修改 成单人 @成员、修改 协助 @成员、修改 奖金 3000、修改 分配 姓名=2000、确认、取消。");
        }
    }

    static String legacySlash(String command) {
        if (command == null) return null;
        String normalized = command.trim();
        if ("下一页".equals(normalized) || "next".equalsIgnoreCase(normalized)) return "/next";
        if ("上一页".equals(normalized) || "prev".equalsIgnoreCase(normalized)) return "/prev";
        if ("成单".equals(normalized)) return "/deal";
        if ("打开CRM".equalsIgnoreCase(normalized) || "客户系统".equals(normalized)) return "/crm";
        if ("打开成单页面".equals(normalized)) return "/deal-web";
        if ("确认".equals(normalized)) return "/confirm";
        if ("取消".equals(normalized)) return "/cancel";
        if (!normalized.startsWith("修改 ")) return null;
        String value = normalized.substring(3).trim();
        if (value.startsWith("成单人 ")) return "/primary " + value.substring(4).trim();
        if (value.startsWith("协助 ")) return "/assist " + value.substring(3).trim();
        if (value.startsWith("订单 ")) return "/order " + value.substring(3).trim();
        if (value.startsWith("奖金 ")) return "/bonus " + value.substring(3).trim();
        if (value.startsWith("分配 ")) return "/split " + value.substring(3).trim();
        return null;
    }

    private void processCard(Setting setting, Identity identity, String chatId, Map<String, Object> value,
                             String eventId) {
        if (slash.processCard(slashContext(setting, identity), chatId, identity.membershipId(), value, eventId)) {
            return;
        }
        String action = String.valueOf(value.getOrDefault("action", ""));
        Session session = requireSession(setting.integrationId(), chatId, identity.membershipId());
        if ("select_customer".equals(action)) {
            UUID customerId = parseUuid(value.get("customerId"));
            CurrentActor actor = actors.require();
            CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
            customers.requireProfileEditable(customer, actor);
            if (customer.getArchivedAt() != null) throw ApiException.conflict("CUSTOMER_ARCHIVED", "归档客户不能成单");
            jdbc.update("""
                    update feishu_deal_bot_sessions set customer_id=?,status='SELECTED',updated_at=now(),
                        expires_at=now()+interval '15 minutes',version=version+1 where id=?
                    """, customerId, session.id());
            queueSelectedCard(setting, chatId, customer, eventId);
        } else if ("start_deal".equals(action)) {
            startDraft(setting, session, actors.require(), eventId);
        } else if ("confirm_deal".equals(action)) {
            confirm(setting, session, eventId);
        } else if ("cancel_deal".equals(action)) {
            cancel(setting, session, eventId);
        } else if ("search_page".equals(action)) {
            int page = Math.max(0, Integer.parseInt(String.valueOf(value.getOrDefault("page", "0"))));
            queueSearchCard(setting, updateSearchPage(session, page), eventId);
        }
    }

    private Session upsertSearch(Setting setting, Identity identity, String chatId, String messageId,
                                 String query, int page) {
        jdbc.update("""
                update feishu_deal_bot_sessions set status='EXPIRED',updated_at=now()
                where integration_id=? and chat_id=? and membership_id=?
                  and status in ('SEARCHING','SELECTED','DRAFT') and expires_at<=now()
                """, setting.integrationId(), chatId, identity.membershipId());
        int changed = jdbc.update("""
                update feishu_deal_bot_sessions set customer_id=null,primary_membership_id=null,
                    assistant_membership_ids='{}',bonus_total_cents=0,allocations='{}'::jsonb,
                    search_query=?,search_page=?,status='SEARCHING',edit_count=0,last_message_id=?,
                    expires_at=now()+interval '15 minutes',updated_at=now(),version=version+1
                where integration_id=? and chat_id=? and membership_id=?
                  and status in ('SEARCHING','SELECTED','DRAFT')
                """, query, page, messageId, setting.integrationId(), chatId, identity.membershipId());
        if (changed == 0) {
            jdbc.update("""
                    insert into feishu_deal_bot_sessions
                    (id,integration_id,organization_id,chat_id,feishu_user_id,membership_id,search_query,
                     search_page,status,last_message_id,expires_at)
                    values (?,?,?,?,?,?,?,?,'SEARCHING',?,now()+interval '15 minutes')
                    """, UUID.randomUUID(), setting.integrationId(), setting.organizationId(), chatId,
                    identity.feishuUserId(), identity.membershipId(), query, page, messageId);
        }
        return requireSession(setting.integrationId(), chatId, identity.membershipId());
    }

    private Session updateSearchPage(Session session, int page) {
        if (session.searchQuery() == null) throw ApiException.conflict("FEISHU_DEAL_SEARCH_REQUIRED", "请先搜索客户");
        jdbc.update("""
                update feishu_deal_bot_sessions set search_page=?,status='SEARCHING',
                    expires_at=now()+interval '15 minutes',updated_at=now(),version=version+1 where id=?
                """, page, session.id());
        return requireSession(session.integrationId(), session.chatId(), session.membershipId());
    }

    private void queueSearchCard(Setting setting, Session session, String eventId) {
        List<CustomerHit> hits = search(session.searchQuery());
        int from = session.searchPage() * 5;
        if (from >= hits.size() && session.searchPage() > 0) {
            session = updateSearchPage(session, 0);
            from = 0;
        }
        int to = Math.min(hits.size(), from + 5);
        List<CustomerHit> page = hits.subList(Math.min(from, hits.size()), to);
        List<Map<String, Object>> elements = new ArrayList<>();
        if (page.isEmpty()) {
            elements.add(markdown("未找到可编辑的客户。请换姓名、昵称、联系方式或学校关键词。"));
        } else {
            for (CustomerHit hit : page) {
                String detail = "**" + safe(hit.name()) + "**\n" + safe(blank(hit.school(), "未填学校"))
                        + " · 负责人 " + safe(blank(hit.owner(), "未知")) + " · 最近跟进 "
                        + (hit.lastContactAt() == null ? "暂无" : TIME.format(hit.lastContactAt()));
                elements.add(markdown(detail));
                elements.add(button("选择客户", "select_customer", Map.of("customerId", hit.id().toString())));
            }
        }
        List<Map<String, Object>> actions = new ArrayList<>();
        if (session.searchPage() > 0) actions.add(button("上一页", "search_page", Map.of("page", session.searchPage() - 1)));
        if (to < hits.size()) actions.add(button("下一页", "search_page", Map.of("page", session.searchPage() + 1)));
        if (!actions.isEmpty()) elements.add(Map.of("tag", "action", "actions", actions));
        queueInteractive(setting, session.chatId(), eventId + ":search:" + session.searchPage(),
                card("客户搜索 · 第 " + (session.searchPage() + 1) + " 页", elements));
    }

    private List<CustomerHit> search(String query) {
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
            if (score < 0) continue;
            hits.add(new CustomerHit(customer.getId(), customer.getFullName(), customer.getSchool(),
                    customer.getOwnerDisplayName(), customer.getLastContactAt(), score));
        }
        hits.sort(Comparator.comparingInt(CustomerHit::score).reversed()
                .thenComparing(CustomerHit::lastContactAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(CustomerHit::name));
        return hits.size() > 100 ? hits.subList(0, 100) : hits;
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

    private void startDraft(Setting setting, Session session, CurrentActor actor, String eventId) {
        if (session.customerId() == null) throw ApiException.conflict("FEISHU_DEAL_CUSTOMER_REQUIRED", "请先搜索并选择客户");
        CustomerEntity customer = customers.requireVisibleEntity(session.customerId(), actor);
        customers.requireProfileEditable(customer, actor);
        if (customer.getArchivedAt() != null) throw ApiException.conflict("CUSTOMER_ARCHIVED", "归档客户不能成单");
        Map<String, Long> allocation = Map.of(actor.membershipId().toString(), setting.defaultBonusCents());
        jdbc.update("""
                update feishu_deal_bot_sessions set primary_membership_id=?,assistant_membership_ids='{}',
                    bonus_total_cents=?,allocations=cast(? as jsonb),status='DRAFT',edit_count=0,
                    expires_at=now()+interval '15 minutes',updated_at=now(),version=version+1 where id=?
                """, actor.membershipId(), setting.defaultBonusCents(), json(allocation), session.id());
        queueDraftCard(setting, requireSession(session.integrationId(), session.chatId(), session.membershipId()), eventId);
    }

    private void modifyDraft(Setting setting, Session session, EventMessage message, String command, String eventId) {
        requireDraftEditable(session);
        String expanded = expandMentions(command, message.getMentions(), setting.botOpenId());
        UUID primary = session.primaryMembershipId();
        List<UUID> assistants = new ArrayList<>(session.assistantMembershipIds());
        long total = session.bonusTotalCents();
        LinkedHashMap<UUID, Long> allocations = allocations(session);
        if (expanded.startsWith("成单人 ")) {
            Person person = requirePerson(setting.organizationId(), expanded.substring(4).trim());
            primary = person.id();
            assistants.remove(primary);
            allocations.clear();
            allocations.put(primary, total);
            assistants.forEach(id -> allocations.put(id, 0L));
        } else if (expanded.startsWith("协助")) {
            String names = expanded.substring(2).trim();
            assistants.clear();
            if (!names.isBlank() && !"无".equals(names)) {
                for (String name : names.split("[\s,，、]+")) {
                    if (name.isBlank()) continue;
                    UUID id = requirePerson(setting.organizationId(), name).id();
                    if (id.equals(primary) || assistants.contains(id)) throw ApiException.badRequest(
                            "DEAL_PARTICIPANT_DUPLICATE", "主成单人和协助人不能重复");
                    assistants.add(id);
                }
            }
            if (assistants.size() > 2) throw ApiException.badRequest("DEAL_ASSISTANT_LIMIT", "协助人最多两名");
            allocations.clear();
            allocations.put(primary, total);
            assistants.forEach(id -> allocations.put(id, 0L));
        } else if (expanded.startsWith("奖金 ")) {
            total = parseCents(expanded.substring(3).trim());
            allocations.clear();
            allocations.put(primary, total);
            assistants.forEach(id -> allocations.put(id, 0L));
        } else if (expanded.startsWith("订单 ")) {
            long orderAmount = parsePositiveCents(expanded.substring(3).trim(), "订单金额");
            jdbc.update("update feishu_deal_bot_sessions set order_amount_cents=? where id=?", orderAmount, session.id());
        } else if (expanded.startsWith("分配 ")) {
            allocations.clear();
            for (String item : expanded.substring(3).trim().split("[\s,，]+")) {
                String[] pair = item.split("=", 2);
                if (pair.length != 2) throw ApiException.badRequest("DEAL_ALLOCATION_INVALID", "分配格式应为：姓名=金额");
                Person person = requirePerson(setting.organizationId(), pair[0].trim());
                if (!person.id().equals(primary) && !assistants.contains(person.id())) {
                    throw ApiException.badRequest("DEAL_ALLOCATION_PARTICIPANTS_MISMATCH", "只能为当前成单参与人分配奖金");
                }
                if (allocations.put(person.id(), parseCents(pair[1].trim())) != null) {
                    throw ApiException.badRequest("DEAL_PARTICIPANT_DUPLICATE", "同一人不能重复分配");
                }
            }
            Set<UUID> expected = new LinkedHashSet<>();
            expected.add(primary);
            expected.addAll(assistants);
            if (!allocations.keySet().equals(expected)) throw ApiException.badRequest(
                    "DEAL_ALLOCATION_PARTICIPANTS_MISMATCH", "必须为所有成单参与人分配奖金");
            long sum = allocations.values().stream().mapToLong(Long::longValue).sum();
            if (sum != total) throw ApiException.badRequest("DEAL_ALLOCATION_SUM_MISMATCH", "个人奖金合计必须严格等于总奖金");
        } else {
            throw ApiException.badRequest("FEISHU_DEAL_MODIFY_COMMAND_INVALID", "无法识别修改指令");
        }
        jdbc.update("""
                update feishu_deal_bot_sessions set primary_membership_id=?,assistant_membership_ids=?,
                    bonus_total_cents=?,allocations=cast(? as jsonb),edit_count=edit_count+1,
                    expires_at=now()+interval '15 minutes',updated_at=now(),version=version+1 where id=?
                """, primary, assistants.toArray(UUID[]::new), total, stringifyAllocations(allocations), session.id());
        queueDraftCard(setting, requireSession(session.integrationId(), session.chatId(), session.membershipId()), eventId);
    }

    private void confirm(Setting setting, Session session, String eventId) {
        if (!"DRAFT".equals(session.status())) throw ApiException.conflict("FEISHU_DEAL_DRAFT_REQUIRED", "请先发起成单草稿");
        UUID requestId = confirmations.confirmFromFeishu(new DealConfirmationService.FeishuConfirmation(
                session.customerId(), session.primaryMembershipId(), session.assistantMembershipIds(),
                session.orderAmountCents(), session.bonusTotalCents(), allocations(session), eventId));
        jdbc.update("""
                update feishu_deal_bot_sessions set status='CONFIRMED',confirmed_request_id=?,updated_at=now(),
                    version=version+1 where id=? and status='DRAFT'
                """, requestId, session.id());
        queueText(setting, session.chatId(), eventId + ":confirmed",
                "✅ 成单已确认并计入榜单，全员广播已进入发送队列。");
    }

    private void cancel(Setting setting, Session session, String eventId) {
        jdbc.update("""
                update feishu_deal_bot_sessions set status='CANCELLED',updated_at=now(),version=version+1
                where id=? and status in ('SEARCHING','SELECTED','DRAFT')
                """, session.id());
        queueText(setting, session.chatId(), eventId + ":cancelled", "已取消当前成单会话。");
    }

    private void queueDraftCard(Setting setting, Session session, String eventId) {
        CustomerEntity customer = customers.requireVisibleEntity(session.customerId(), actors.require());
        List<String> lines = new ArrayList<>();
        lines.add("**客户**：" + safe(customer.getFullName()));
        lines.add("**主成单人**：" + safe(personName(session.primaryMembershipId())));
        lines.add("**协助**：" + (session.assistantMembershipIds().isEmpty() ? "无" : session.assistantMembershipIds().stream()
                .map(this::personName).reduce((a, b) -> a + "、" + b).orElse("无")));
        lines.add("**订单金额**：" + (session.orderAmountCents() > 0 ? "¥" + formatCents(session.orderAmountCents()) : "待填写"));
        lines.add("**奖金总额**：¥" + formatCents(session.bonusTotalCents()));
        lines.add("**分配**：" + allocations(session).entrySet().stream()
                .map(entry -> personName(entry.getKey()) + "=¥" + formatCents(entry.getValue()))
                .reduce((a, b) -> a + "；" + b).orElse("未分配"));
        lines.add("**剩余可修改次数**：" + Math.max(0, 3 - session.editCount()));
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(markdown(String.join("\n", lines)));
        appendCardButtons(elements, List.of(
                button("确认成单", "confirm_deal", Map.of()),
                button("取消", "cancel_deal", Map.of())));
        queueInteractive(setting, session.chatId(), eventId + ":draft:" + session.version(),
                card("成单确认", elements));
    }

    private void queueSelectedCard(Setting setting, String chatId, CustomerEntity customer, String eventId) {
        List<Map<String, Object>> elements = new ArrayList<>();
        elements.add(markdown("已选择客户 **" + safe(customer.getFullName()) + "**\n"
                + safe(blank(customer.getSchool(), "未填学校")) + " · 负责人 "
                + safe(blank(customer.getOwnerDisplayName(), "未知"))));
        appendCardButtons(elements, List.of(
                button("发起成单", "start_deal", Map.of()),
                button("取消", "cancel_deal", Map.of())));
        queueInteractive(setting, chatId, eventId + ":selected", card("客户已选择", elements));
    }

    /** Feishu card schema 2.0 accepts interactive buttons as body elements. */
    static void appendCardButtons(List<Map<String, Object>> elements, List<Map<String, Object>> buttons) {
        elements.addAll(buttons);
    }

    private void requireDraftEditable(Session session) {
        if (!"DRAFT".equals(session.status())) throw ApiException.conflict("FEISHU_DEAL_DRAFT_REQUIRED", "请先发起成单草稿");
        if (session.editCount() >= 3) throw ApiException.conflict("FEISHU_DEAL_EDIT_LIMIT_REACHED", "已达到三次修改上限，请确认或取消");
    }

    private Session requireSession(UUID integrationId, String chatId, UUID membershipId) {
        Session session = activeSession(integrationId, chatId, membershipId);
        if (session == null) throw ApiException.conflict("FEISHU_DEAL_SESSION_NOT_FOUND", "当前没有可用会话，请先使用 /search");
        return session;
    }

    private Session activeSession(UUID integrationId, String chatId, UUID membershipId) {
        jdbc.update("""
                update feishu_deal_bot_sessions set status='EXPIRED',updated_at=now()
                where integration_id=? and chat_id=? and membership_id=?
                  and status in ('SEARCHING','NEW_PENDING','SELECTED','DRAFT') and expires_at<=now()
                """, integrationId, chatId, membershipId);
        return jdbc.query("""
                select * from feishu_deal_bot_sessions where integration_id=? and chat_id=? and membership_id=?
                  and status in ('SEARCHING','NEW_PENDING','SELECTED','DRAFT') and expires_at>now()
                """, this::mapSession, integrationId, chatId, membershipId).stream().findFirst().orElse(null);
    }

    private Session mapSession(ResultSet rs, int row) throws SQLException {
        Array array = rs.getArray("assistant_membership_ids");
        List<UUID> assistants = array == null ? List.of() : Arrays.asList((UUID[]) array.getArray());
        return new Session(rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("chat_id"), rs.getString("feishu_user_id"),
                rs.getObject("membership_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getObject("primary_membership_id", UUID.class), assistants, rs.getLong("bonus_total_cents"),
                rs.getLong("order_amount_cents"), rs.getString("allocations"), rs.getString("search_query"), rs.getInt("search_page"),
                rs.getString("status"), rs.getInt("edit_count"), rs.getTimestamp("expires_at").toInstant(),
                rs.getLong("version"));
    }

    private LinkedHashMap<UUID, Long> allocations(Session session) {
        try {
            Map<String, Long> source = json.readValue(session.allocations(), new TypeReference<>() {});
            LinkedHashMap<UUID, Long> result = new LinkedHashMap<>();
            if (session.primaryMembershipId() != null && source.containsKey(session.primaryMembershipId().toString())) {
                result.put(session.primaryMembershipId(), source.get(session.primaryMembershipId().toString()));
            }
            for (UUID id : session.assistantMembershipIds()) if (source.containsKey(id.toString())) result.put(id, source.get(id.toString()));
            return result;
        } catch (Exception exception) {
            throw new IllegalStateException("Invalid stored deal allocation", exception);
        }
    }

    private String stringifyAllocations(Map<UUID, Long> allocations) {
        Map<String, Long> values = new LinkedHashMap<>();
        allocations.forEach((key, value) -> values.put(key.toString(), value));
        return json(values);
    }

    private Person requirePerson(UUID organizationId, String name) {
        String clean = name == null ? "" : name.replace("@", "").trim();
        List<Person> people = jdbc.query("""
                select distinct membership.id,account.display_name from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                join membership_roles assignment on assignment.membership_id=membership.id
                join roles role on role.id=assignment.role_id and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER')
                where membership.organization_id=? and membership.status='ACTIVE' and account.display_name=?
                """, (rs, row) -> new Person(rs.getObject("id", UUID.class), rs.getString("display_name")),
                organizationId, clean);
        if (people.size() != 1) throw ApiException.badRequest("FEISHU_DEAL_PERSON_AMBIGUOUS", "成单参与人不存在或姓名重复：" + clean);
        return people.getFirst();
    }

    private String personName(UUID membershipId) {
        return jdbc.query("""
                select account.display_name from memberships membership join user_accounts account on account.id=membership.user_id
                where membership.id=?
                """, (rs, row) -> rs.getString("display_name"), membershipId).stream().findFirst().orElse("未知成员");
    }

    private Identity identity(Setting setting, String senderTenantKey, String headerTenantKey, UserId id) {
        return identity(setting, senderTenantKey, headerTenantKey, id.getUserId(), id.getOpenId(), id.getUnionId());
    }

    private Identity identity(Setting setting, String senderTenantKey, String headerTenantKey,
                              String userId, String openId, String unionId) {
        String tenantKey = resolveEventTenantKey(senderTenantKey, headerTenantKey, setting.tenantKey());
        if (tenantKey == null) return null;
        List<BoundIdentity> bindings = jdbc.query("""
                select binding.membership_id,binding.feishu_user_id,binding.open_id,binding.union_id
                from feishu_identity_bindings binding
                join memberships membership on membership.id=binding.membership_id and membership.status='ACTIVE'
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                where binding.integration_id=? and binding.tenant_key=? and binding.active=true
                  and exists (select 1 from membership_roles assignment join roles role on role.id=assignment.role_id
                              where assignment.membership_id=membership.id
                                and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER'))
                """, (rs, row) -> new BoundIdentity(rs.getObject("membership_id", UUID.class),
                rs.getString("feishu_user_id"), rs.getString("open_id"), rs.getString("union_id")),
                setting.integrationId(), tenantKey);
        List<BoundIdentity> matches = bindings.stream()
                .filter(binding -> identitiesMatch(binding.feishuUserId(), binding.openId(), binding.unionId(),
                        userId, openId, unionId))
                .toList();
        if (matches.size() != 1) return null;
        BoundIdentity match = matches.getFirst();
        return new Identity(match.membershipId(), match.feishuUserId());
    }

    static String resolveEventTenantKey(String senderTenantKey, String headerTenantKey,
                                        String integrationTenantKey) {
        String sender = normalized(senderTenantKey);
        String header = normalized(headerTenantKey);
        String configured = normalized(integrationTenantKey);
        if (sender != null && header != null && !sender.equals(header)) return null;
        String eventTenant = sender != null ? sender : header;
        if (configured != null && eventTenant != null && !configured.equals(eventTenant)) return null;
        return configured != null ? configured : eventTenant;
    }

    static boolean identitiesMatch(String boundUserId, String boundOpenId, String boundUnionId,
                                   String eventUserId, String eventOpenId, String eventUnionId) {
        Set<String> bound = nonBlankSet(boundUserId, boundOpenId, boundUnionId);
        Set<String> event = nonBlankSet(eventUserId, eventOpenId, eventUnionId);
        return !bound.isEmpty() && !event.isEmpty() && bound.stream().anyMatch(event::contains);
    }

    private static Set<String> nonBlankSet(String... values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            String normalized = normalized(value);
            if (normalized != null) result.add(normalized);
        }
        return result;
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Setting setting(UUID integrationId) {
        return jdbc.query("""
                select setting.*,integration.tenant_key integration_tenant_key
                from feishu_deal_bot_settings setting
                join feishu_bitable_integrations integration on integration.id=setting.integration_id
                where setting.integration_id=?
                """, (rs, row) ->
                new Setting(rs.getObject("integration_id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getBoolean("enabled"), rs.getString("submission_chat_id"), rs.getString("broadcast_chat_id"),
                        rs.getString("bot_open_id"), rs.getLong("default_bonus_cents"),
                        rs.getString("integration_tenant_key"), rs.getBoolean("quick_customer_creation_enabled")), integrationId)
                .stream().findFirst().orElse(null);
    }

    private FeishuDealBotSlashService.Context slashContext(Setting setting, Identity identity) {
        return new FeishuDealBotSlashService.Context(setting.integrationId(), setting.organizationId(),
                setting.submissionChatId(), setting.botOpenId(), identity.feishuUserId(),
                setting.defaultBonusCents(), setting.quickCustomerCreationEnabled());
    }

    private UUID remember(UUID integrationId, UUID organizationId, String eventId, String messageId,
                          String eventType, String chatId, String senderId) {
        UUID id = UUID.randomUUID();
        String stableEventId = eventId == null || eventId.isBlank()
                ? "message:" + (messageId == null ? id : messageId) : eventId;
        try {
            jdbc.update("""
                    insert into feishu_deal_bot_event_inbox
                    (id,integration_id,organization_id,event_id,message_id,event_type,chat_id,sender_feishu_user_id)
                    values (?,?,?,?,?,?,?,?)
                    """, id, integrationId, organizationId, stableEventId, messageId,
                    eventType == null ? "unknown" : eventType, chatId, senderId);
            return id;
        } catch (DuplicateKeyException duplicate) {
            return null;
        }
    }

    private void finish(UUID id, String status, String error) {
        jdbc.update("""
                update feishu_deal_bot_event_inbox set status=?,error_code=?,processed_at=now() where id=?
                """, status, error, id);
    }

    private void queueText(Setting setting, String chatId, String key, String text) {
        queue(setting, chatId, "text", Map.of("text", text), key);
    }

    private void queueInteractive(Setting setting, String chatId, String key, Map<String, Object> card) {
        queue(setting, chatId, "interactive", card, key);
    }

    private void queue(Setting setting, String chatId, String type, Map<String, Object> payload, String key) {
        UUID messageUuid = UUID.nameUUIDFromBytes((setting.integrationId() + ":" + key).getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
                insert into feishu_deal_message_outbox
                (id,integration_id,organization_id,chat_id,message_type,payload,message_uuid,purpose)
                values (?,?,?,?,?,cast(? as jsonb),?,'BOT_REPLY')
                on conflict (integration_id,message_uuid) do nothing
                """, UUID.randomUUID(), setting.integrationId(), setting.organizationId(), chatId, type,
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
                "type", action.startsWith("confirm") || action.startsWith("select") ? "primary" : "default",
                "value", value);
    }

    private String commandText(EventMessage message, String botOpenId) {
        try {
            JsonNode content = json.readTree(message.getContent());
            return expandMentions(content.path("text").asText(""), message.getMentions(), botOpenId).trim();
        } catch (Exception exception) {
            throw ApiException.badRequest("FEISHU_MESSAGE_INVALID", "飞书消息格式无效");
        }
    }

    private String expandMentions(String text, MentionEvent[] mentions, String botOpenId) {
        String result = text == null ? "" : text;
        if (mentions == null) return result;
        for (MentionEvent mention : mentions) {
            if (mention == null || mention.getKey() == null) continue;
            boolean bot = mention.getId() != null && botOpenId != null && botOpenId.equals(mention.getId().getOpenId());
            result = result.replace(mention.getKey(), bot ? "" : mention.getName());
        }
        return result;
    }

    private boolean mentionsBot(MentionEvent[] mentions, String botOpenId) {
        if (mentions == null || botOpenId == null) return false;
        return Arrays.stream(mentions).filter(java.util.Objects::nonNull).map(MentionEvent::getId)
                .filter(java.util.Objects::nonNull).anyMatch(id -> botOpenId.equals(id.getOpenId()));
    }

    private long parseCents(String value) {
        try {
            BigDecimal amount = new BigDecimal(value.replace("¥", "").replace("元", "").trim());
            if (amount.signum() < 0 || amount.scale() > 2) throw new NumberFormatException();
            return amount.movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (Exception exception) {
            throw ApiException.badRequest("DEAL_BONUS_INVALID", "奖金必须是非负数且最多两位小数");
        }
    }

    private long parsePositiveCents(String value, String label) {
        long cents = parseCents(value);
        if (cents <= 0) throw ApiException.badRequest("DEAL_AMOUNT_INVALID", label + "必须大于 0");
        return cents;
    }

    private UUID parseUuid(Object value) {
        try {
            return UUID.fromString(String.valueOf(value));
        } catch (Exception exception) {
            throw ApiException.badRequest("FEISHU_DEAL_CUSTOMER_INVALID", "客户选择已失效，请重新搜索");
        }
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean equals(String value, String needle) { return value != null && value.trim().equalsIgnoreCase(needle); }
    private boolean starts(String value, String needle) { return value != null && value.toLowerCase(Locale.ROOT).startsWith(needle); }
    private boolean contains(String value, String needle) { return value != null && value.toLowerCase(Locale.ROOT).contains(needle); }
    private String digits(String value) { return value == null ? "" : value.replaceAll("\\D", ""); }
    private String safe(String value) { return value == null ? "" : value.replace("<", "＜").replace(">", "＞"); }
    private String blank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private String formatCents(long cents) { return BigDecimal.valueOf(cents, 2).stripTrailingZeros().toPlainString(); }

    private record Setting(UUID integrationId, UUID organizationId, boolean enabled, String submissionChatId,
                           String broadcastChatId, String botOpenId, long defaultBonusCents, String tenantKey,
                           boolean quickCustomerCreationEnabled) {}
    private record Identity(UUID membershipId, String feishuUserId) {}
    private record BoundIdentity(UUID membershipId, String feishuUserId, String openId, String unionId) {}
    private record Person(UUID id, String name) {}
    private record CustomerHit(UUID id, String name, String school, String owner, Instant lastContactAt, int score) {}
    private record Session(UUID id, UUID integrationId, UUID organizationId, String chatId, String feishuUserId,
                           UUID membershipId, UUID customerId, UUID primaryMembershipId, List<UUID> assistantMembershipIds,
                           long bonusTotalCents, long orderAmountCents, String allocations, String searchQuery, int searchPage, String status,
                           int editCount, Instant expiresAt, long version) {}
}
