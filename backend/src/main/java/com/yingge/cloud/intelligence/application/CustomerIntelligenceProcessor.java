package com.yingge.cloud.intelligence.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.assistant.application.AiProperties;
import com.yingge.cloud.assistant.application.AiSettingsService;
import com.yingge.cloud.assistant.infrastructure.OpenAiCompatibleProvider;
import com.yingge.cloud.intelligence.api.CustomerIntelligenceDtos;
import com.yingge.cloud.intelligence.infrastructure.CustomerIntelligenceStore;
import com.yingge.cloud.intelligence.infrastructure.LocalEmbeddingClient;
import com.yingge.cloud.shared.api.ApiException;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CustomerIntelligenceProcessor {
    private static final List<String> REQUIRED_RESULT_FIELDS = List.of(
            "executiveSummary", "goals", "motivations", "progress", "concerns",
            "decisionParticipants", "productFit", "risks", "recommendedActions",
            "nextFollowUpSuggestion", "unresolvedQuestions", "citations");
    private static final String SYSTEM_PROMPT = """
            你是严格的客户跟进分析助手。仅根据给定的客户画像和时间线证据分析，不得猜测、不得填补未出现的事实。
            信息不足时显式写“未确认”。你的输出是只读建议，不修改客户正式档案。
            返回严格 JSON，必须包含：executiveSummary、goals、motivations、progress、concerns、
            decisionParticipants、productFit、risks、recommendedActions、nextFollowUpSuggestion、
            unresolvedQuestions、citations。citations 只能使用证据中已给出的 sourceKey。
            """;

    private final CustomerIntelligenceQueue queue;
    private final CustomerIntelligenceStore store;
    private final JdbcTemplate jdbc;
    private final LangChainCustomerChunker chunker;
    private final LocalEmbeddingClient embeddings;
    private final AiSettingsService aiSettings;
    private final RestClient.Builder restClients;
    private final ObjectMapper mapper;
    private final AtomicBoolean processing = new AtomicBoolean();

    public CustomerIntelligenceProcessor(
            CustomerIntelligenceQueue queue,
            CustomerIntelligenceStore store,
            JdbcTemplate jdbc,
            LangChainCustomerChunker chunker,
            LocalEmbeddingClient embeddings,
            AiSettingsService aiSettings,
            RestClient.Builder restClients,
            ObjectMapper mapper) {
        this.queue = queue;
        this.store = store;
        this.jdbc = jdbc;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.aiSettings = aiSettings;
        this.restClients = restClients;
        this.mapper = mapper;
    }

    @Scheduled(
            fixedDelayString = "${app.customer-intelligence.worker-delay:5000}",
            initialDelayString = "${app.customer-intelligence.worker-initial-delay:30000}")
    public void processNext() {
        if (!processing.compareAndSet(false, true)) return;
        CustomerIntelligenceQueue.Job job = null;
        long started = System.nanoTime();
        try {
            job = queue.claimNext();
            if (job == null) return;
            process(job, started);
            queue.succeed(job.id());
        } catch (Exception exception) {
            if (job != null) {
                String code = exception instanceof ApiException api ? api.code()
                        : exception instanceof LocalEmbeddingClient.EmbeddingUnavailableException embedding
                        ? embedding.code() : "CUSTOMER_ANALYSIS_FAILED";
                try {
                    store.recordFailure(job, code, elapsed(started));
                } catch (RuntimeException auditFailure) {
                    code = "AI_AUDIT_WRITE_FAILED";
                }
                queue.fail(job, code, exception.getMessage());
            }
        } finally {
            processing.set(false);
        }
    }

    void process(CustomerIntelligenceQueue.Job job, long started) {
        IntelligenceSettings settings = settings(job.organizationId());
        if (!settings.enabled()) throw ApiException.unavailable("CUSTOMER_INTELLIGENCE_DISABLED", "客户智能分析已关闭");
        CustomerSource customer = customer(job.organizationId(), job.customerId());
        List<InteractionSource> interactions = interactions(job.organizationId(), job.customerId());
        List<SourceDocument> documents = documents(customer, interactions);
        List<CustomerIntelligenceStore.Chunk> chunks = chunks(documents, settings);
        store.replaceChunks(job.organizationId(), job.customerId(), chunks);

        String query = customer.profileText() + "\n" + interactions.stream().limit(3)
                .map(InteractionSource::text).reduce("", (left, right) -> left + "\n" + right);
        float[] queryEmbedding = embedQuery(settings, query);
        List<CustomerIntelligenceStore.Chunk> semantic = store.semanticChunks(
                job.organizationId(), job.customerId(), queryEmbedding, settings.semanticLimit());
        List<SourceDocument> context = context(documents, semantic, settings.recentLimit());
        AiSettingsService.EffectiveConfig ai = aiSettings.effective(job.organizationId());
        if (!ai.configured()) throw ApiException.unavailable("AI_NOT_CONFIGURED", "管理员尚未配置可用的 AI 服务");
        AiProperties properties = new AiProperties(ai.baseUrl(), ai.apiKey(), ai.model(), ai.timeout());
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(properties, restClients.clone());
        String prompt = buildPrompt(customer, context);
        var generated = provider.generate(SYSTEM_PROMPT, prompt, true);
        JsonNode result = parseJson(generated.content());
        Set<String> citedSources = validateCitations(result, context);
        List<CustomerIntelligenceDtos.Evidence> evidence = context.stream()
                .filter(source -> citedSources.contains(source.sourceKey()))
                .map(source -> new CustomerIntelligenceDtos.Evidence(
                        source.sourceType(), source.sourceKey(), source.sourceAt(), excerpt(source.text())))
                .toList();
        Instant watermark = interactions.stream().map(InteractionSource::sourceAt)
                .filter(value -> value != null).max(Instant::compareTo).orElse(customer.updatedAt());
        String narrative = result.path("executiveSummary").asText("暂无可用结论");
        store.saveVersion(job, result, narrative, evidence, provider.providerId(), provider.model(), watermark,
                generated.inputTokens(), generated.outputTokens(), elapsed(started));
    }

    private IntelligenceSettings settings(UUID organizationId) {
        return jdbc.query("""
                select enabled, embedding_base_url, embedding_model,
                       recent_interaction_limit, semantic_chunk_limit
                from customer_intelligence_settings where organization_id=?
                """, (row, index) -> new IntelligenceSettings(
                row.getBoolean("enabled"), row.getString("embedding_base_url"),
                row.getString("embedding_model"), row.getInt("recent_interaction_limit"),
                row.getInt("semantic_chunk_limit")), organizationId)
                .stream().findFirst().orElse(new IntelligenceSettings(
                        true, "http://embedding:8080", "bge-m3", 10, 12));
    }

    private CustomerSource customer(UUID organizationId, UUID customerId) {
        return jdbc.query("""
                select customer.id, customer.full_name, customer.nickname, customer.school,
                       customer.major, customer.grade, customer.city, customer.high_school,
                       customer.source_location, customer.identity_type, customer.demand_summary,
                       customer.intention_level, customer.urgency_level, customer.verification_status,
                       customer.current_status, customer.owner_notes, customer.last_contact_at,
                       customer.next_follow_up_at, customer.updated_at,
                       coalesce(source.label, customer.source_channel_legacy, '未填写') source_name,
                       coalesce(string_agg(distinct tag.name, '、') filter (where tag.id is not null), '') tags
                from customers customer
                left join dictionary_items source on source.id=customer.source_dictionary_id
                left join customer_tags customer_tag on customer_tag.customer_id=customer.id
                left join tags tag on tag.id=customer_tag.tag_id and tag.active=true
                where customer.organization_id=? and customer.id=?
                group by customer.id, source.label
                """, (row, index) -> new CustomerSource(
                row.getObject("id", UUID.class), row.getString("full_name"),
                profileText(row.getString("full_name"), row.getString("nickname"), row.getString("school"),
                        row.getString("major"), row.getString("grade"), row.getString("city"),
                        row.getString("high_school"), row.getString("source_location"),
                        row.getString("identity_type"), row.getString("demand_summary"),
                        row.getString("intention_level"), row.getString("urgency_level"),
                        row.getString("verification_status"), row.getString("current_status"),
                        row.getString("owner_notes"), row.getString("source_name"), row.getString("tags"),
                        row.getTimestamp("last_contact_at"), row.getTimestamp("next_follow_up_at")),
                row.getTimestamp("updated_at").toInstant()), organizationId, customerId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("CUSTOMER_NOT_FOUND", "客户不存在"));
    }

    private List<InteractionSource> interactions(UUID organizationId, UUID customerId) {
        return jdbc.query("""
                select id, contacted_at, channel, summary, details, outcome,
                       verification_todo, next_follow_up_at, created_at
                from interactions
                where organization_id=? and customer_id=?
                order by contacted_at desc, created_at desc
                """, (row, index) -> new InteractionSource(
                row.getObject("id", UUID.class), row.getTimestamp("contacted_at").toInstant(),
                "沟通时间：" + row.getTimestamp("contacted_at").toInstant()
                        + "\n渠道：" + safe(row.getString("channel"))
                        + "\n摘要：" + safe(row.getString("summary"))
                        + "\n详情：" + safe(row.getString("details"))
                        + "\n结果：" + safe(row.getString("outcome"))
                        + "\n待核实：" + safe(row.getString("verification_todo"))
                        + "\n下次跟进：" + timestampText(row.getTimestamp("next_follow_up_at"))),
                organizationId, customerId);
    }

    private List<SourceDocument> documents(CustomerSource customer, List<InteractionSource> interactions) {
        List<SourceDocument> result = new ArrayList<>();
        result.add(new SourceDocument("PROFILE", "profile:" + customer.id(), customer.updatedAt(), customer.profileText()));
        interactions.forEach(interaction -> result.add(new SourceDocument(
                "INTERACTION", "interaction:" + interaction.id(), interaction.sourceAt(), interaction.text())));
        return List.copyOf(result);
    }

    private List<CustomerIntelligenceStore.Chunk> chunks(
            List<SourceDocument> documents, IntelligenceSettings settings) {
        List<PendingChunk> pending = new ArrayList<>();
        for (SourceDocument document : documents) {
            List<TextSegment> segments = chunker.split(document.text());
            for (int index = 0; index < segments.size(); index++) {
                pending.add(new PendingChunk(document.sourceType(), document.sourceKey(), document.sourceAt(),
                        index, segments.get(index).text()));
            }
        }
        List<float[]> vectors;
        try {
            vectors = embedBatches(settings, pending.stream().map(PendingChunk::content).toList());
        } catch (LocalEmbeddingClient.EmbeddingUnavailableException exception) {
            vectors = new ArrayList<>();
            for (int index = 0; index < pending.size(); index++) vectors.add(null);
        }
        List<CustomerIntelligenceStore.Chunk> chunks = new ArrayList<>();
        for (int index = 0; index < pending.size(); index++) {
            PendingChunk item = pending.get(index);
            chunks.add(new CustomerIntelligenceStore.Chunk(
                    item.sourceType(), item.sourceKey(), item.sourceAt(), item.chunkIndex(),
                    item.content(), vectors.get(index)));
        }
        return List.copyOf(chunks);
    }

    private List<float[]> embedBatches(IntelligenceSettings settings, List<String> texts) {
        List<float[]> values = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += 32) {
            values.addAll(embeddings.embed(settings.embeddingBaseUrl(), settings.embeddingModel(),
                    texts.subList(start, Math.min(texts.size(), start + 32))));
        }
        return values;
    }

    private float[] embedQuery(IntelligenceSettings settings, String query) {
        try {
            return embeddings.embed(settings.embeddingBaseUrl(), settings.embeddingModel(), List.of(query)).getFirst();
        } catch (LocalEmbeddingClient.EmbeddingUnavailableException exception) {
            return null;
        }
    }

    private List<SourceDocument> context(
            List<SourceDocument> documents, List<CustomerIntelligenceStore.Chunk> semantic, int recentLimit) {
        Map<String, SourceDocument> selected = new LinkedHashMap<>();
        documents.stream().filter(item -> item.sourceType().equals("PROFILE"))
                .forEach(item -> selected.put(item.sourceKey(), item));
        documents.stream().filter(item -> item.sourceType().equals("INTERACTION"))
                .limit(recentLimit).forEach(item -> selected.put(item.sourceKey(), item));
        semantic.forEach(item -> selected.putIfAbsent(item.sourceKey(), new SourceDocument(
                item.sourceType(), item.sourceKey(), item.sourceAt(), item.content())));
        return List.copyOf(selected.values());
    }

    private String buildPrompt(CustomerSource customer, List<SourceDocument> context) {
        StringBuilder prompt = new StringBuilder("客户：").append(customer.fullName())
                .append("\n\n以下为按 sourceKey 标记的可引用证据：\n");
        for (SourceDocument source : context) {
            prompt.append("\n[").append(source.sourceKey()).append("] ")
                    .append(source.sourceAt() == null ? "" : source.sourceAt()).append('\n')
                    .append(source.text()).append('\n');
        }
        prompt.append("\n请优先识别客户目标、动机、顾虑、当前阶段、转化风险和下一步，每个关键结论必须能追溯到 citations。");
        return prompt.toString();
    }

    private JsonNode parseJson(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            JsonNode result = mapper.readTree(value);
            if (!result.isObject() || REQUIRED_RESULT_FIELDS.stream().anyMatch(field -> !result.hasNonNull(field))) {
                throw ApiException.unavailable("AI_INVALID_JSON", "模型未返回完整的结构化分析");
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw ApiException.unavailable("AI_INVALID_JSON", "模型返回了无法解析的结构");
        }
    }

    private Set<String> validateCitations(JsonNode result, List<SourceDocument> context) {
        Set<String> allowed = context.stream().map(SourceDocument::sourceKey)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        JsonNode citations = result.path("citations");
        if (!citations.isArray() || citations.isEmpty()) {
            throw ApiException.unavailable("AI_CITATION_REQUIRED", "模型未返回可追溯的证据引用");
        }
        Set<String> cited = new LinkedHashSet<>();
        for (JsonNode citation : citations) {
            String value = citation.isTextual() ? citation.asText() : citation.path("sourceKey").asText();
            if (value.isBlank() || !allowed.contains(value)) {
                throw ApiException.unavailable("AI_CITATION_INVALID", "模型返回了不存在的证据引用");
            }
            cited.add(value);
        }
        return Set.copyOf(cited);
    }

    private static String profileText(
            String fullName, String nickname, String school, String major, String grade, String city,
            String highSchool, String sourceLocation, String identity, String demand, String intention,
            String urgency, String verification, String status, String notes, String source, String tags,
            Timestamp lastContact, Timestamp nextFollowUp) {
        return "姓名：" + safe(fullName) + "\n昵称：" + safe(nickname) + "\n大学：" + safe(school)
                + "\n专业：" + safe(major) + "\n年级：" + safe(grade) + "\n所在城市：" + safe(city)
                + "\n高中：" + safe(highSchool) + "\n生源地：" + safe(sourceLocation)
                + "\n身份：" + safe(identity) + "\n需求摘要：" + safe(demand)
                + "\n意向：" + safe(intention) + "\n紧急度：" + safe(urgency)
                + "\n核实：" + safe(verification) + "\n当前状态：" + safe(status)
                + "\n内部备注：" + safe(notes) + "\n来源：" + safe(source) + "\n标签：" + safe(tags)
                + "\n最近沟通：" + timestampText(lastContact) + "\n下次跟进：" + timestampText(nextFollowUp);
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "未确认" : value.strip(); }
    private static String timestampText(Timestamp value) { return value == null ? "未确认" : value.toInstant().toString(); }
    private String excerpt(String value) { return value.length() <= 240 ? value : value.substring(0, 240) + "…"; }
    private long elapsed(long started) { return Duration.ofNanos(System.nanoTime() - started).toMillis(); }

    private record IntelligenceSettings(boolean enabled, String embeddingBaseUrl, String embeddingModel,
                                        int recentLimit, int semanticLimit) {}
    private record CustomerSource(UUID id, String fullName, String profileText, Instant updatedAt) {}
    private record InteractionSource(UUID id, Instant sourceAt, String text) {}
    private record SourceDocument(String sourceType, String sourceKey, Instant sourceAt, String text) {}
    private record PendingChunk(String sourceType, String sourceKey, Instant sourceAt,
                                int chunkIndex, String content) {}
}
