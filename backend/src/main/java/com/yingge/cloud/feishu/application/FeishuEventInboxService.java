package com.yingge.cloud.feishu.application;

import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class FeishuEventInboxService {
    private final JdbcTemplate jdbc;
    private final FeishuIntegrationService integrations;

    public FeishuEventInboxService(JdbcTemplate jdbc, FeishuIntegrationService integrations) {
        this.jdbc = jdbc;
        this.integrations = integrations;
    }

    @Transactional
    public void receive(UUID integrationId, InboundEvent event) {
        FeishuIntegrationService.IntegrationRow integration = integrations.findById(integrationId);
        if (integration == null || !integration.active() || !integration.inboundSyncEnabled()) return;
        if (!integration.appId().equals(event.appId()) || !integration.baseAppToken().equals(event.fileToken())
                || !integration.tableId().equals(event.tableId())) {
            integrations.updateEventState(integrationId, "DEGRADED", "FEISHU_EVENT_SCOPE_MISMATCH", false);
            return;
        }
        if (integration.tenantKey() != null && !integration.tenantKey().equals(event.tenantKey())) {
            integrations.updateEventState(integrationId, "DEGRADED", "FEISHU_EVENT_TENANT_MISMATCH", false);
            return;
        }
        String recordId = identifier(event.recordId(), "FEISHU_EVENT_RECORD_INVALID");
        String action = required(event.action(), "FEISHU_EVENT_ACTION_INVALID").toUpperCase();
        if (!(action.contains("ADDED") || action.contains("EDITED") || action.contains("DELETED"))) {
            return;
        }
        try {
            jdbc.update("""
                    insert into feishu_event_inbox
                    (id,integration_id,organization_id,event_id,event_type,tenant_key,table_id,record_id,
                     action_kind,operator_user_id,operator_open_id)
                    values (?,?,?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), integration.id(), integration.organizationId(),
                    limited(required(event.eventId(), "FEISHU_EVENT_ID_INVALID"), 160),
                    limited(required(event.eventType(), "FEISHU_EVENT_TYPE_INVALID"), 160),
                    limited(event.tenantKey(), 128), integration.tableId(), recordId, limited(action, 32),
                    limited(event.operatorUserId(), 128), limited(event.operatorOpenId(), 128));
        } catch (DuplicateKeyException ignored) {
            // Feishu retries events; the compound uniqueness key makes reception idempotent.
        }
        integrations.updateEventState(integrationId, "CONNECTED", null, true);
    }

    private String identifier(String value, String code) {
        String clean = required(value, code);
        if (!clean.matches("[A-Za-z0-9_-]{3,256}")) throw ApiException.badRequest(code, "飞书事件标识无效");
        return clean;
    }

    private String required(String value, String code) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(code, "飞书事件缺少必要字段");
        return value.trim();
    }

    private String limited(String value, int max) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    public record InboundEvent(String eventId, String eventType, String appId, String tenantKey,
                               String fileToken, String tableId, String recordId, String action,
                               String operatorUserId, String operatorOpenId) {}
}
