package com.yingge.cloud.mcp.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.mcp.McpProperties;
import com.yingge.cloud.mcp.oauth.McpOAuthService;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class McpApprovalService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final McpProperties properties;
    private final CurrentActorProvider actors;
    private final AuditService audit;

    public McpApprovalService(JdbcTemplate jdbc, ObjectMapper mapper, McpProperties properties,
                              CurrentActorProvider actors, AuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
        this.actors = actors;
        this.audit = audit;
    }

    @Transactional
    public ApprovalRequired requireOrCreate(McpOAuthService.TokenAuthentication token, String toolName,
                                            Map<String, Object> arguments, String summary) {
        UUID approvalId = uuid(arguments.get("approvalId"));
        String argumentsHash = argumentsHash(arguments);
        if (approvalId == null) {
            UUID id = UUID.randomUUID();
            Instant now = Instant.now();
            jdbc.update("""
                    insert into mcp_approval_requests
                        (id, organization_id, membership_id, client_id, tool_name, arguments_hash,
                         summary, status, created_at, expires_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """, id, token.organizationId(), token.membershipId(), token.clientId(), toolName,
                    argumentsHash, summary, java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(now.plus(properties.approvalTtl())));
            return new ApprovalRequired(id, properties.normalizedIssuer() + "/agent/approvals/" + id,
                    summary, now.plus(properties.approvalTtl()), false);
        }
        int consumed = jdbc.update("""
                update mcp_approval_requests
                set status='CONSUMED', consumed_at=now()
                where id=? and membership_id=? and client_id=? and tool_name=? and arguments_hash=?
                  and status='APPROVED' and expires_at>now()
                """, approvalId, token.membershipId(), token.clientId(), toolName, argumentsHash);
        if (consumed != 1) {
            throw ApiException.conflict("MCP_APPROVAL_INVALID",
                    "人工确认不存在、未批准、已过期或与操作参数不匹配");
        }
        return new ApprovalRequired(approvalId, null, summary, Instant.now(), true);
    }

    @Transactional(readOnly = true)
    public List<ApprovalView> mine() {
        var actor = actors.require();
        return jdbc.query("""
                select id, client_id, tool_name, summary, status, created_at, expires_at, decided_at, consumed_at
                from mcp_approval_requests where membership_id=? order by created_at desc limit 100
                """, (result, index) -> view(result), actor.membershipId());
    }

    @Transactional(readOnly = true)
    public ApprovalView get(UUID id) {
        var actor = actors.require();
        return jdbc.query("""
                select id, client_id, tool_name, summary, status, created_at, expires_at, decided_at, consumed_at
                from mcp_approval_requests where id=? and membership_id=?
                """, (result, index) -> view(result), id, actor.membershipId()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("MCP_APPROVAL_NOT_FOUND", "确认请求不存在"));
    }

    @Transactional
    public ApprovalView decide(UUID id, boolean approve) {
        var actor = actors.require();
        int changed = jdbc.update("""
                update mcp_approval_requests set status=?, decided_at=now()
                where id=? and membership_id=? and status='PENDING' and expires_at>now()
                """, approve ? "APPROVED" : "REJECTED", id, actor.membershipId());
        if (changed != 1) throw ApiException.conflict("MCP_APPROVAL_NOT_PENDING", "确认请求已处理或过期");
        audit.record(actor, approve ? "MCP_ACTION_APPROVED" : "MCP_ACTION_REJECTED",
                "MCP_APPROVAL", id.toString(), "SUCCESS", null);
        return get(id);
    }

    private ApprovalView view(java.sql.ResultSet result) throws java.sql.SQLException {
        Instant expires = result.getTimestamp("expires_at").toInstant();
        String status = result.getString("status");
        if ("PENDING".equals(status) && !expires.isAfter(Instant.now())) status = "EXPIRED";
        return new ApprovalView(result.getObject("id", UUID.class), result.getString("client_id"),
                result.getString("tool_name"), result.getString("summary"), status,
                result.getTimestamp("created_at").toInstant(), expires,
                instant(result.getTimestamp("decided_at")), instant(result.getTimestamp("consumed_at")));
    }

    private String argumentsHash(Map<String, Object> arguments) {
        Map<String, Object> normalized = new java.util.TreeMap<>(arguments);
        normalized.remove("approvalId");
        normalized.remove("idempotencyKey");
        try {
            byte[] bytes = mapper.writeValueAsBytes(canonical(normalized));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
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

    private UUID uuid(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try { return UUID.fromString(value.toString()); }
        catch (IllegalArgumentException exception) { throw ApiException.badRequest("INVALID_APPROVAL_ID", "approvalId 格式无效"); }
    }
    private Instant instant(java.sql.Timestamp value) { return value == null ? null : value.toInstant(); }

    public record ApprovalRequired(UUID approvalId, String approvalUrl, String summary,
                                   Instant expiresAt, boolean consumed) {}
    public record ApprovalView(UUID id, String clientId, String toolName, String summary, String status,
                               Instant createdAt, Instant expiresAt, Instant decidedAt, Instant consumedAt) {}
}
