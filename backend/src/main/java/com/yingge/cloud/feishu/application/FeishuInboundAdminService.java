package com.yingge.cloud.feishu.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.feishu.api.FeishuIntegrationDtos;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FeishuInboundAdminService {
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;

    public FeishuInboundAdminService(JdbcTemplate jdbc, CurrentActorProvider actors,
                                     PermissionService permissions, AuditService audit) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public FeishuIntegrationDtos.InboundStatusResponse status() {
        CurrentActor actor = requireAdmin();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select integration.event_state,integration.last_event_at,integration.last_event_error_code,
                       count(inbox.id) filter (where inbox.status in ('RECEIVED','PROCESSING')) pending_count,
                       count(inbox.id) filter (where inbox.status='APPLIED') applied_count,
                       count(inbox.id) filter (where inbox.status='CONFLICT') conflict_count,
                       count(inbox.id) filter (where inbox.status in ('REJECTED','RESTORED')) rejected_count,
                       count(inbox.id) filter (where inbox.status='FAILED') failed_count
                from feishu_bitable_integrations integration
                left join feishu_event_inbox inbox on inbox.integration_id=integration.id
                where integration.organization_id=?
                group by integration.id
                """, actor.organizationId());
        if (rows.isEmpty()) {
            return new FeishuIntegrationDtos.InboundStatusResponse("DISABLED", null, null, 0, 0, 0, 0, 0);
        }
        Map<String, Object> row = rows.getFirst();
        return new FeishuIntegrationDtos.InboundStatusResponse(
                string(row.get("event_state")), instant(row.get("last_event_at")),
                string(row.get("last_event_error_code")), number(row.get("pending_count")),
                number(row.get("applied_count")), number(row.get("conflict_count")),
                number(row.get("rejected_count")), number(row.get("failed_count")));
    }

    @Transactional(readOnly = true)
    public List<FeishuIntegrationDtos.InboundEventResponse> events(String status, int size) {
        CurrentActor actor = requireAdmin();
        String normalized = normalizeStatus(status);
        int limit = Math.min(Math.max(size, 1), 200);
        return jdbc.query("""
                select inbox.id,inbox.event_type,inbox.action_kind,inbox.record_id,inbox.status,
                       inbox.attempt_count,inbox.last_error_code,inbox.received_at,inbox.processed_at,
                       change.customer_id,change.actor_membership_id,change.changed_fields,
                       coalesce(change.reason_code,inbox.last_error_code) reason_code
                from feishu_event_inbox inbox
                join feishu_bitable_integrations integration on integration.id=inbox.integration_id
                left join lateral (
                    select customer_id,actor_membership_id,changed_fields,reason_code
                    from feishu_inbound_changes where inbox_event_id=inbox.id
                    order by created_at desc limit 1
                ) change on true
                where integration.organization_id=?
                  and inbox.status=coalesce(cast(? as varchar),inbox.status)
                order by inbox.received_at desc limit ?
                """, (rs, index) -> new FeishuIntegrationDtos.InboundEventResponse(
                rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("action_kind"),
                rs.getString("record_id"), rs.getString("status"), rs.getInt("attempt_count"),
                rs.getString("reason_code"), rs.getObject("customer_id", UUID.class),
                rs.getObject("actor_membership_id", UUID.class), strings(rs.getArray("changed_fields")),
                rs.getTimestamp("received_at").toInstant(), instant(rs.getTimestamp("processed_at"))),
                actor.organizationId(), normalized, limit);
    }

    @Transactional
    public void retry(UUID inboxId) {
        CurrentActor actor = requireAdmin();
        int changed = jdbc.update("""
                update feishu_event_inbox inbox
                set status='RECEIVED',attempt_count=0,next_attempt_at=now(),last_error_code=null,processed_at=null
                from feishu_bitable_integrations integration
                where inbox.id=? and inbox.integration_id=integration.id and integration.organization_id=?
                  and inbox.status in ('CONFLICT','REJECTED','RESTORED','FAILED')
                """, inboxId, actor.organizationId());
        if (changed == 0) {
            throw ApiException.conflict("FEISHU_EVENT_NOT_RETRYABLE", "该入站事件不存在或当前不可重试");
        }
        audit.record(actor, "FEISHU_INBOUND_EVENT_RETRIED", "FEISHU_EVENT_INBOX", inboxId.toString(),
                "SUCCESS", null);
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return actor;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = status.trim().toUpperCase();
        if (!List.of("RECEIVED", "PROCESSING", "APPLIED", "IGNORED", "RESTORED", "CONFLICT", "REJECTED", "FAILED")
                .contains(normalized)) {
            throw ApiException.badRequest("FEISHU_EVENT_STATUS_INVALID", "飞书事件状态无效");
        }
        return normalized;
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Instant instant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : null;
    }

    private List<String> strings(Array value) throws SQLException {
        if (value == null) return List.of();
        Object array = value.getArray();
        return array instanceof String[] strings ? Arrays.asList(strings) : List.of();
    }
}
