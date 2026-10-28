package com.yingge.cloud.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.assistant.api.AssistantDtos;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AssistantFacade {
    private static final Logger log = LoggerFactory.getLogger(AssistantFacade.class);
    private static final String PRIVACY_SYSTEM = """
            你是英格营销团队的内部客户管理助手。只能根据提供的最小上下文回答；不得猜测未提供的个人信息，
            不得要求访问其他客户或数据库，不得输出 SQL。输出必须是简体中文，涉及事实时区分已知与待确认。
            """;

    private final AiProvider provider;
    private final ObjectMapper objectMapper;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final CustomerService customers;
    private final InteractionService interactions;
    private final JdbcTemplate jdbc;
    private final QqConversationDraftParser qqDraftParser;

    public AssistantFacade(AiProvider provider, ObjectMapper objectMapper, CurrentActorProvider actors,
                           PermissionService permissions, CustomerService customers,
                           InteractionService interactions, JdbcTemplate jdbc) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.actors = actors;
        this.permissions = permissions;
        this.customers = customers;
        this.interactions = interactions;
        this.jdbc = jdbc;
        this.qqDraftParser = new QqConversationDraftParser(objectMapper);
    }

    public AssistantDtos.Availability availability() {
        return new AssistantDtos.Availability(provider.available(), provider.providerId(), provider.model());
    }

    @Transactional(readOnly = true)
    public void requireCustomerAccess(UUID customerId) {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        customers.requireVisibleEntity(customerId, actor);
    }

    public AssistantDtos.CustomerDraft parseCustomerDraft(String text) {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        String schema = """
                把用户文本解析为 JSON 客户草稿。字段：fullName,nickname,phone,wechatId,qqId,school,major,grade,city,
                identityType,focusDirections(数组),demandSummary,intentionLevel,urgencyLevel,verificationStatus,
                sourceChannel,currentStatus,ownerNotes,nextFollowUpAt(ISO-8601或null),uncertainties(数组)。
                不确定的字段必须为 null 并写入 uncertainties，不能编造。
                """;
        return structured("PARSE_CUSTOMER_DRAFT", null, PRIVACY_SYSTEM + schema, text, AssistantDtos.CustomerDraft.class);
    }

    public AssistantDtos.InteractionDraft parseInteractionDraft(String text) {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        String schema = """
                把用户文本解析为 JSON 沟通草稿。字段：contactedAt(ISO-8601或null),channel,summary,details,outcome,
                verificationTodo,nextFollowUpAt(ISO-8601或null),currentStatus,intentionLevel,verificationStatus,
                uncertainties(数组)。不确定字段必须为 null，不能编造。
                """;
        return structured("PARSE_INTERACTION_DRAFT", null, PRIVACY_SYSTEM + schema, text, AssistantDtos.InteractionDraft.class);
    }

    public AssistantDtos.QqConversationDraft summarizeQqConversation(UUID customerId, String messages) {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        customers.requireVisibleEntity(customerId, actor);
        String schema = """
                把工作 QQ 会话整理为严格 JSON。输入中的每行带有“目标客户”“销售/其他”或“群聊上下文”证据角色。
                字段：summary,customerQuotes(数组),goalsMotivation,
                actionsTaken,mainConcerns,decisionParticipants,productFit,suggestedFocus,explicitTaboos,
                outcome,nextStep,nextFollowUpAt(ISO-8601或null),uncertainties(数组)。
                只能使用消息中的事实；无法确认时填“未确认”并加入 uncertainties。
                只有标记为“目标客户”的消息可以作为客户事实或 customerQuotes；群聊上下文只能辅助理解，
                绝不能把第三方或销售的观点、经历和意向归到客户名下。
                decisionParticipants 只提取对话中客户自愿明确提及的必要事实，不推断家庭信息。
                customerQuotes 只选最关键的短句，不超过 8 条。
                必须直接返回一个 JSON 对象，不要输出思考过程、解释、Markdown 或额外包装层。
                customerQuotes 和 uncertainties 必须始终为 JSON 数组；nextFollowUpAt 只能是带时区的
                ISO-8601 字符串或 null，不能使用空字符串或“未确认”。
                """;
        String bounded = messages == null ? "" : messages.substring(0, Math.min(messages.length(), 60_000));
        return structuredQqConversation("QQ_CONVERSATION_DRAFT", customerId, PRIVACY_SYSTEM + schema, bounded);
    }

    @Transactional
    public AssistantDtos.ConsultationDraft consultationDraft(UUID customerId) {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        var timeline = interactions.list(customerId).stream().limit(50).toList();
        if (timeline.isEmpty()) {
            throw ApiException.badRequest("CONSULTATION_TIMELINE_REQUIRED", "请先记录真实沟通，再生成咨询交接草稿");
        }
        String schema = """
                根据单个客户档案和时间线生成严格 JSON 咨询交接草稿。字段：consultationReason,
                customerQuotes,goalsMotivation,actionsTaken,mainConcerns,decisionParticipants,productFit,
                suggestedFocus,explicitTaboos,expectedResult,citations(数组，每项 interactionId 和 evidence),
                uncertainties(数组)。只能使用输入中的事实，无法确认时填“未确认”并列入 uncertainties；
                每条事实引用的 interactionId 必须来自输入时间线；不得推断家庭或决策参与人信息。
                """;
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("customer", Map.of("name", customer.getFullName(), "school", nullSafe(customer.getSchool()),
                "major", nullSafe(customer.getMajor()), "grade", nullSafe(customer.getGrade()),
                "demand", nullSafe(customer.getDemandSummary()), "status", customer.getCurrentStatus()));
        context.put("timeline", timeline);
        AssistantDtos.ConsultationDraft generated = structured("CONSULTATION_HANDOFF_DRAFT", customerId,
                PRIVACY_SYSTEM + schema, json(context), AssistantDtos.ConsultationDraft.class);
        var allowed = timeline.stream().map(item -> item.id()).collect(java.util.stream.Collectors.toSet());
        List<AssistantDtos.InteractionCitation> citations = generated.citations() == null ? List.of()
                : generated.citations().stream().filter(item -> item != null && item.interactionId() != null
                        && allowed.contains(item.interactionId())).limit(50).toList();
        List<String> uncertainties = generated.uncertainties() == null ? List.of() : generated.uncertainties();
        if (citations.isEmpty()) {
            uncertainties = new java.util.ArrayList<>(uncertainties);
            uncertainties.add("AI 未提供有效时间线引用，请人工选择来源记录");
        }
        return new AssistantDtos.ConsultationDraft(generated.consultationReason(), generated.customerQuotes(),
                generated.goalsMotivation(), generated.actionsTaken(), generated.mainConcerns(),
                generated.decisionParticipants(), generated.productFit(), generated.suggestedFocus(),
                generated.explicitTaboos(), generated.expectedResult(), citations, List.copyOf(uncertainties));
    }

    @Transactional
    public String summarizeCustomerTimeline(UUID customerId) {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        var timeline = interactions.list(customerId).stream().limit(50).toList();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("customer", Map.of(
                "name", customer.getFullName(),
                "status", customer.getCurrentStatus(),
                "intention", customer.getIntentionLevel(),
                "verification", customer.getVerificationStatus(),
                "demand", nullSafe(customer.getDemandSummary()),
                "nextFollowUpAt", customer.getNextFollowUpAt() == null ? "" : customer.getNextFollowUpAt().toString()
        ));
        context.put("recentInteractions", timeline);
        return text("SUMMARIZE_TIMELINE", customerId, PRIVACY_SYSTEM,
                "请基于以下单个客户上下文总结进展、未确认信息、风险和下一步。\n" + json(context));
    }

    @Transactional
    public String recommendNextActions(UUID customerId) {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        var context = Map.of("status", customer.getCurrentStatus(), "intention", customer.getIntentionLevel(),
                "verification", customer.getVerificationStatus(), "demand", nullSafe(customer.getDemandSummary()),
                "lastContactAt", customer.getLastContactAt() == null ? "" : customer.getLastContactAt().toString());
        return text("RECOMMEND_NEXT_ACTIONS", customerId, PRIVACY_SYSTEM,
                "给出最多 5 条具体下一步建议，并说明依据。上下文：" + json(context));
    }

    @Transactional
    public String generateDailyBriefing() {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        Instant now = Instant.now();
        List<Map<String, Object>> candidates = customers.visibleEntities(actor, false).stream()
                .filter(row -> (row.getNextFollowUpAt() != null && row.getNextFollowUpAt().isBefore(now.plus(Duration.ofDays(1))))
                        || ("高".equals(row.getIntentionLevel()) && (row.getLastContactAt() == null
                        || row.getLastContactAt().isBefore(now.minus(Duration.ofDays(3))))))
                .limit(25)
                .map(row -> Map.<String, Object>of(
                        "customerId", row.getId().toString(), "name", row.getFullName(),
                        "status", row.getCurrentStatus(), "intention", row.getIntentionLevel(),
                        "nextFollowUpAt", row.getNextFollowUpAt() == null ? "" : row.getNextFollowUpAt().toString()
                )).toList();
        return text("DAILY_BRIEFING", null, PRIVACY_SYSTEM,
                "基于以下已通过权限过滤的候选客户生成今日简报，按紧急性排序：" + json(candidates));
    }

    @Transactional
    public String generateMobileGreeting(String salutation) {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        String prompt = "请为员工工作台生成一句自然、克制的中文副问候，不超过24个汉字。"
                + "不要包含客户隐私、承诺或未经提供的事实，不要重复主问候。当前主问候：" + salutation;
        return text("MOBILE_GREETING", null, PRIVACY_SYSTEM, prompt).trim();
    }

    @Transactional
    public String detectDataQualityIssues(UUID customerId) {
        var actor = actors.require();
        permissions.requireAssistant(actor);
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        var minimal = Map.of("name", customer.getFullName(), "school", nullSafe(customer.getSchool()),
                "major", nullSafe(customer.getMajor()), "grade", nullSafe(customer.getGrade()),
                "verification", customer.getVerificationStatus(), "demand", nullSafe(customer.getDemandSummary()));
        return text("DATA_QUALITY", customerId, PRIVACY_SYSTEM,
                "仅指出缺失、互相矛盾或需要人工核实的数据，不要补写信息：" + json(minimal));
    }

    private <T> T structured(String purpose, UUID customerId, String system, String prompt, Class<T> type) {
        long started = System.nanoTime();
        try {
            AiProvider.AiResult result = provider.generate(system, prompt, true);
            T parsed = objectMapper.readValue(stripFences(result.content()), type);
            recordRun(purpose, customerId, "SUCCESS", result, elapsed(started), null);
            return parsed;
        } catch (ApiException exception) {
            recordRun(purpose, customerId, "FAILED", null, elapsed(started), exception.code());
            throw exception;
        } catch (Exception exception) {
            log.warn("AI structured response rejected purpose={} exception={}", purpose,
                    exception.getClass().getSimpleName());
            recordRun(purpose, customerId, "FAILED", null, elapsed(started), "AI_INVALID_JSON");
            throw ApiException.unavailable("AI_INVALID_JSON", "模型返回的草稿格式无效，请重试");
        }
    }

    private AssistantDtos.QqConversationDraft structuredQqConversation(String purpose, UUID customerId,
                                                                         String system, String prompt) {
        long started = System.nanoTime();
        try {
            AiProvider.AiResult result = provider.generate(system, prompt, true);
            AssistantDtos.QqConversationDraft parsed = qqDraftParser.parse(result.content());
            recordRun(purpose, customerId, "SUCCESS", result, elapsed(started), null);
            return parsed;
        } catch (ApiException exception) {
            recordRun(purpose, customerId, "FAILED", null, elapsed(started), exception.code());
            throw exception;
        } catch (Exception exception) {
            log.warn("AI QQ draft response rejected exception={}", exception.getClass().getSimpleName());
            recordRun(purpose, customerId, "FAILED", null, elapsed(started), "AI_INVALID_JSON");
            throw ApiException.unavailable("AI_INVALID_JSON", "模型返回的草稿格式无效，请重试");
        }
    }

    private String text(String purpose, UUID customerId, String system, String prompt) {
        long started = System.nanoTime();
        try {
            AiProvider.AiResult result = provider.generate(system, prompt, false);
            recordRun(purpose, customerId, "SUCCESS", result, elapsed(started), null);
            return result.content();
        } catch (ApiException exception) {
            recordRun(purpose, customerId, "FAILED", null, elapsed(started), exception.code());
            throw exception;
        }
    }

    private void recordRun(String purpose, UUID customerId, String status, AiProvider.AiResult result,
                           long latency, String errorCode) {
        var actor = actors.require();
        jdbc.update("""
                insert into assistant_runs (id, organization_id, actor_membership_id, customer_id, purpose,
                    provider, model, status, input_tokens, output_tokens, latency_ms, error_code)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.organizationId(), actor.membershipId(), customerId, purpose,
                provider.providerId(), provider.model(), status,
                result == null ? null : result.inputTokens(), result == null ? null : result.outputTokens(), latency, errorCode);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String stripFences(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return trimmed;
    }
    private long elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }
    private String nullSafe(String value) { return value == null ? "" : value; }
}
