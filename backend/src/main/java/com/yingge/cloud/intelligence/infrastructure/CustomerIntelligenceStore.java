package com.yingge.cloud.intelligence.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.intelligence.api.CustomerIntelligenceDtos;
import com.yingge.cloud.intelligence.application.CustomerIntelligenceQueue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Repository
public class CustomerIntelligenceStore {
    private static final TypeReference<List<CustomerIntelligenceDtos.Evidence>> EVIDENCE_TYPE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CustomerIntelligenceStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional
    public void replaceChunks(UUID organizationId, UUID customerId, List<Chunk> chunks) {
        jdbc.update("delete from customer_knowledge_chunks where organization_id=? and customer_id=?",
                organizationId, customerId);
        for (Chunk chunk : chunks) {
            if (chunk.embedding() == null) {
                jdbc.update("""
                        insert into customer_knowledge_chunks
                          (id, organization_id, customer_id, source_type, source_key, source_at,
                           chunk_index, content, content_hash)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), organizationId, customerId, chunk.sourceType(),
                        chunk.sourceKey(), timestamp(chunk.sourceAt()), chunk.chunkIndex(),
                        chunk.content(), sha256(chunk.content()));
            } else {
                jdbc.update("""
                        insert into customer_knowledge_chunks
                          (id, organization_id, customer_id, source_type, source_key, source_at,
                           chunk_index, content, content_hash, embedding)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as vector))
                        """, UUID.randomUUID(), organizationId, customerId, chunk.sourceType(),
                        chunk.sourceKey(), timestamp(chunk.sourceAt()), chunk.chunkIndex(),
                        chunk.content(), sha256(chunk.content()), vectorLiteral(chunk.embedding()));
            }
        }
    }

    public List<Chunk> semanticChunks(UUID organizationId, UUID customerId, float[] query, int limit) {
        if (query == null) return List.of();
        return jdbc.query("""
                select source_type, source_key, source_at, chunk_index, content, embedding::text
                from customer_knowledge_chunks
                where organization_id=? and customer_id=? and embedding is not null
                order by embedding <=> cast(? as vector), source_at desc nulls last
                limit ?
                """, (row, index) -> new Chunk(
                row.getString("source_type"), row.getString("source_key"),
                row.getTimestamp("source_at") == null ? null : row.getTimestamp("source_at").toInstant(),
                row.getInt("chunk_index"), row.getString("content"), null),
                organizationId, customerId, vectorLiteral(query), limit);
    }

    @Transactional
    public CustomerIntelligenceDtos.Version saveVersion(
            CustomerIntelligenceQueue.Job job, JsonNode result, String narrative,
            List<CustomerIntelligenceDtos.Evidence> evidence, String provider, String model,
            Instant sourceWatermark, Integer inputTokens, Integer outputTokens, long latencyMs) {
        Integer next = jdbc.queryForObject("""
                select coalesce(max(version_number), 0) + 1
                from customer_analysis_versions
                where organization_id=? and customer_id=?
                """, Integer.class, job.organizationId(), job.customerId());
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        jdbc.update("""
                insert into customer_analysis_versions
                  (id, organization_id, customer_id, version_number, trigger_type, status,
                   structured_result, narrative, evidence, provider, model, source_watermark,
                   created_by_membership_id, created_at)
                values (?, ?, ?, ?, ?, 'SUCCEEDED', cast(? as jsonb), ?, cast(? as jsonb), ?, ?, ?, ?, ?)
                """, id, job.organizationId(), job.customerId(), next, job.trigger().name(),
                json(result), narrative, json(evidence), provider, model, timestamp(sourceWatermark),
                job.actorMembershipId(), Timestamp.from(createdAt));
        recordRun(job, provider, model, "SUCCESS", inputTokens, outputTokens, latencyMs, null);
        return new CustomerIntelligenceDtos.Version(
                id, next, job.trigger().name(), "SUCCEEDED", result, narrative, evidence,
                provider, model, sourceWatermark, createdAt);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(CustomerIntelligenceQueue.Job job, String errorCode, long latencyMs) {
        recordRun(job, "customer-intelligence", null, "FAILED", null, null, latencyMs, errorCode);
    }

    public List<CustomerIntelligenceDtos.Version> versions(UUID organizationId, UUID customerId, int limit) {
        return jdbc.query("""
                select id, version_number, trigger_type, status, structured_result, narrative,
                       evidence, provider, model, source_watermark, created_at
                from customer_analysis_versions
                where organization_id=? and customer_id=?
                order by version_number desc limit ?
                """, (row, index) -> new CustomerIntelligenceDtos.Version(
                row.getObject("id", UUID.class), row.getInt("version_number"),
                row.getString("trigger_type"), row.getString("status"),
                readTree(row.getString("structured_result")), row.getString("narrative"),
                evidence(row.getString("evidence")), row.getString("provider"), row.getString("model"),
                row.getTimestamp("source_watermark") == null ? null : row.getTimestamp("source_watermark").toInstant(),
                row.getTimestamp("created_at").toInstant()), organizationId, customerId, limit);
    }

    public boolean isSuccessfulVersionForCustomer(UUID organizationId, UUID customerId, UUID versionId) {
        Boolean present = jdbc.queryForObject("""
                select exists(
                    select 1
                    from customer_analysis_versions
                    where id=? and organization_id=? and customer_id=? and status='SUCCEEDED'
                )
                """, Boolean.class, versionId, organizationId, customerId);
        return Boolean.TRUE.equals(present);
    }

    private Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private String vectorLiteral(float[] vector) {
        StringBuilder value = new StringBuilder(vector.length * 8).append('[');
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) value.append(',');
            value.append(Float.toString(vector[index]));
        }
        return value.append(']').toString();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法序列化客户智能分析", exception); }
    }

    private JsonNode readTree(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法读取客户智能分析", exception); }
    }

    private List<CustomerIntelligenceDtos.Evidence> evidence(String value) {
        try { return mapper.readValue(value, EVIDENCE_TYPE); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法读取分析证据", exception); }
    }

    private void recordRun(
            CustomerIntelligenceQueue.Job job, String provider, String model, String status,
            Integer inputTokens, Integer outputTokens, long latencyMs, String errorCode) {
        jdbc.update("""
                insert into assistant_runs
                  (id, organization_id, actor_membership_id, customer_id, purpose,
                   provider, model, status, input_tokens, output_tokens, latency_ms, error_code)
                values (?, ?, ?, ?, 'CUSTOMER_INTELLIGENCE', ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), job.organizationId(), job.actorMembershipId(), job.customerId(),
                provider, model, status, inputTokens, outputTokens, latencyMs, errorCode);
    }

    public record Chunk(String sourceType, String sourceKey, Instant sourceAt,
                        int chunkIndex, String content, float[] embedding) {}
}
