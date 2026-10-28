package com.yingge.cloud.mcp.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.mcp.oauth.McpOAuthService;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class McpIdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public McpIdempotencyService(JdbcTemplate jdbc, ObjectMapper mapper,
                                 org.springframework.transaction.PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
    }

    public Object execute(McpOAuthService.TokenAuthentication token, String toolName,
                          Map<String, Object> arguments, Supplier<Object> action) {
        String key = text(arguments.get("idempotencyKey"));
        if (key == null) return action.get();
        if (key.length() > 160) throw ApiException.badRequest("IDEMPOTENCY_KEY_TOO_LONG", "idempotencyKey 不能超过 160 字符");
        String hash = hash(arguments);
        Object cached = cached(token, toolName, key, hash);
        if (cached != null) return cached;
        try {
            return transactions.execute(status -> {
                Instant now = Instant.now();
                jdbc.update("delete from mcp_idempotency_records where expires_at<=now()");
                jdbc.update("""
                        insert into mcp_idempotency_records
                            (id, membership_id, client_id, tool_name, idempotency_key, arguments_hash,
                             status, created_at, expires_at)
                        values (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                        """, UUID.randomUUID(), token.membershipId(), token.clientId(), toolName, key, hash,
                        java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(86_400)));
                Object result = action.get();
                jdbc.update("""
                        update mcp_idempotency_records
                        set status='SUCCESS', result=cast(? as jsonb)
                        where membership_id=? and client_id=? and tool_name=? and idempotency_key=?
                        """, json(result), token.membershipId(), token.clientId(), toolName, key);
                return result;
            });
        } catch (DuplicateKeyException exception) {
            Object concurrent = cached(token, toolName, key, hash);
            if (concurrent != null) return concurrent;
            throw ApiException.conflict("MCP_REQUEST_IN_PROGRESS", "相同幂等请求正在处理，请稍后重试");
        }
    }

    private Object cached(McpOAuthService.TokenAuthentication token, String tool, String key, String hash) {
        return jdbc.query("""
                select arguments_hash, status, result from mcp_idempotency_records
                where membership_id=? and client_id=? and tool_name=? and idempotency_key=? and expires_at>now()
                """, (result, index) -> {
            if (!hash.equals(result.getString("arguments_hash"))) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "同一 idempotencyKey 不能用于不同参数");
            }
            if (!"SUCCESS".equals(result.getString("status"))) return Pending.INSTANCE;
            try { return mapper.readValue(result.getString("result"), Object.class); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }, token.membershipId(), token.clientId(), tool, key).stream().findFirst().map(value -> {
            if (value == Pending.INSTANCE) throw ApiException.conflict("MCP_REQUEST_IN_PROGRESS", "相同幂等请求正在处理");
            return value;
        }).orElse(null);
    }

    private String hash(Map<String, Object> arguments) {
        Map<String, Object> normalized = new java.util.TreeMap<>(arguments);
        normalized.remove("idempotencyKey");
        normalized.remove("approvalId");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsBytes(canonical(normalized)))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof java.util.Collection<?> collection) return collection.stream().map(this::canonical).toList();
        return value;
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String text(Object value) { return value == null || value.toString().isBlank() ? null : value.toString(); }
    private enum Pending { INSTANCE }
}
