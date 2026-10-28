package com.yingge.cloud.feishu.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.feishu.api.FeishuIntegrationDtos;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import com.yingge.cloud.feishu.infrastructure.FeishuSecretCipher;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FeishuIntegrationService {
    private static final String TABLE_NAME = "英格云客户档案";
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;
    private final FeishuSecretCipher cipher;
    private final FeishuBitableClient client;

    public FeishuIntegrationService(JdbcTemplate jdbc, CurrentActorProvider actors, PermissionService permissions,
                                    AuditService audit, FeishuSecretCipher cipher, FeishuBitableClient client) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
        this.cipher = cipher;
        this.client = client;
    }

    @Transactional(readOnly = true)
    public FeishuIntegrationDtos.ConfigurationResponse configuration() {
        CurrentActor actor = requireAdmin();
        return response(findByOrganization(actor.organizationId()));
    }

    @Transactional
    public FeishuIntegrationDtos.ConfigurationResponse save(FeishuIntegrationDtos.ConfigurationRequest body) {
        CurrentActor actor = requireAdmin();
        IntegrationRow current = findByOrganization(actor.organizationId());
        String appId = identifier(body.appId(), 128, "FEISHU_APP_ID_INVALID", "飞书 App ID 格式无效");
        String baseToken = identifier(body.baseAppToken(), 256, "FEISHU_BASE_TOKEN_INVALID", "多维表格 Base App Token 格式无效");
        boolean riskAcknowledged = Boolean.TRUE.equals(body.riskAcknowledged())
                || current != null && current.riskAcknowledgedAt() != null;
        if (!riskAcknowledged) {
            throw ApiException.badRequest("FEISHU_RISK_ACKNOWLEDGEMENT_REQUIRED", "请确认完整联系方式和私有客户同步风险");
        }
        String newSecret = cleanSecret(body.appSecret());
        if (current == null && newSecret == null) {
            throw ApiException.badRequest("FEISHU_APP_SECRET_REQUIRED", "首次配置必须填写飞书 App Secret");
        }
        boolean desiredActive = Boolean.TRUE.equals(body.active());
        boolean desiredOauth = body.oauthEnabled() == null ? current != null && current.oauthEnabled()
                : Boolean.TRUE.equals(body.oauthEnabled());
        boolean desiredInbound = body.inboundSyncEnabled() == null ? current != null && current.inboundSyncEnabled()
                : Boolean.TRUE.equals(body.inboundSyncEnabled());
        if (desiredActive && (current == null || current.tableId() == null || !"READY".equals(current.schemaState()))) {
            throw ApiException.conflict("FEISHU_SCHEMA_NOT_READY", "请先测试连接并完成数据表初始化");
        }
        if (desiredInbound && !desiredActive) {
            throw ApiException.conflict("FEISHU_OUTBOUND_REQUIRED", "启用双向协同前必须先启用客户同步");
        }
        UUID id;
        boolean secretRotated = newSecret != null;
        if (current == null) {
            id = UUID.randomUUID();
            try {
                jdbc.update("""
                        insert into feishu_bitable_integrations
                        (id,organization_id,app_id,encrypted_app_secret,base_app_token,active,risk_acknowledged_at,
                         created_by_membership_id,updated_by_membership_id,oauth_enabled,inbound_sync_enabled,event_state)
                        values (?,?,?,?,?,?,now(),?,?,?,?,?)
                        """, id, actor.organizationId(), appId, cipher.encrypt(newSecret), baseToken, false,
                        actor.membershipId(), actor.membershipId(), desiredOauth, false, "DISABLED");
            } catch (DuplicateKeyException exception) {
                throw ApiException.conflict("FEISHU_INTEGRATION_EXISTS", "本组织已经存在飞书集成配置");
            }
        } else {
            id = current.id();
            long expected = body.expectedVersion() == null ? -1 : body.expectedVersion();
            boolean targetChanged = !appId.equals(current.appId()) || !baseToken.equals(current.baseAppToken());
            byte[] encrypted = newSecret == null ? current.encryptedSecret() : cipher.encrypt(newSecret);
            int changed = jdbc.update("""
                    update feishu_bitable_integrations
                    set app_id=?,encrypted_app_secret=?,base_app_token=?,active=?,oauth_enabled=?,inbound_sync_enabled=?,
                        table_id=case when ? then null else table_id end,
                        schema_state=case when ? then 'NOT_PROVISIONED' else schema_state end,
                        event_state=case when ? then 'CONNECTING' else 'DISABLED' end,
                        event_subscription_authorized_at=case when ? then null else event_subscription_authorized_at end,
                        updated_by_membership_id=?,updated_at=now(),version=version+1,
                        last_error_code=case when ? then null else last_error_code end
                    where id=? and organization_id=? and (?<0 or version=?)
                    """, appId, encrypted, baseToken, desiredActive && !targetChanged, desiredOauth,
                    desiredInbound && desiredActive && !targetChanged, targetChanged, targetChanged,
                    desiredInbound && desiredActive && !targetChanged,
                    targetChanged, actor.membershipId(), targetChanged, id, actor.organizationId(), expected, expected);
            if (changed == 0) throw ApiException.conflict("FEISHU_CONFIG_VERSION_CONFLICT", "飞书配置已被修改，请刷新后重试");
            if (targetChanged) {
                jdbc.update("""
                        update feishu_bitable_sync_runs
                        set status='FAILED',last_error_code='FEISHU_TARGET_CHANGED',completed_at=now(),updated_at=now()
                        where integration_id=? and status in ('PENDING','RUNNING')
                        """, id);
                jdbc.update("delete from feishu_bitable_fields where integration_id=?", id);
                jdbc.update("delete from feishu_bitable_record_links where integration_id=?", id);
                jdbc.update("delete from feishu_bitable_sync_queue where integration_id=?", id);
                client.invalidateToken(id);
            } else if (secretRotated) {
                client.invalidateToken(id);
            }
        }
        audit.record(actor, current == null ? "FEISHU_INTEGRATION_CREATED" : "FEISHU_INTEGRATION_UPDATED",
                "FEISHU_INTEGRATION", id.toString(), "SUCCESS",
                Map.of("appId", appId, "baseChanged", current != null && !baseToken.equals(current.baseAppToken()),
                        "secretRotated", secretRotated));
        return response(findByOrganization(actor.organizationId()));
    }

    @Transactional(noRollbackFor = ApiException.class)
    public FeishuIntegrationDtos.ConnectionTestResponse testConnection() {
        CurrentActor actor = requireAdmin();
        IntegrationRow row = requireConfiguration(actor.organizationId());
        Instant testedAt = Instant.now();
        try {
            client.testBase(credentials(row), row.baseAppToken());
            jdbc.update("""
                    update feishu_bitable_integrations set last_tested_at=?,last_error_code=null,updated_at=now()
                    where id=?
                    """, Timestamp.from(testedAt), row.id());
            audit.record(actor, "FEISHU_INTEGRATION_TESTED", "FEISHU_INTEGRATION", row.id().toString(),
                    "SUCCESS", null);
            return new FeishuIntegrationDtos.ConnectionTestResponse(true, testedAt);
        } catch (RuntimeException exception) {
            String code = safeError(exception);
            jdbc.update("""
                    update feishu_bitable_integrations set last_tested_at=?,last_error_code=?,updated_at=now()
                    where id=?
                    """, Timestamp.from(testedAt), code, row.id());
            audit.record(actor, "FEISHU_INTEGRATION_TESTED", "FEISHU_INTEGRATION", row.id().toString(),
                    "FAILED", Map.of("errorCode", code));
            throw ApiException.unavailable(code, "飞书连接测试失败，请检查应用权限和 Base 授权");
        }
    }

    @Transactional(noRollbackFor = ApiException.class)
    public FeishuIntegrationDtos.ProvisionResponse provision() {
        CurrentActor actor = requireAdmin();
        IntegrationRow row = requireConfiguration(actor.organizationId());
        String tableId = row.tableId();
        try {
            if (tableId == null) {
                tableId = client.createTable(credentials(row), row.baseAppToken(), TABLE_NAME,
                        FeishuCustomerSchema.FIELDS).tableId();
                jdbc.update("""
                        update feishu_bitable_integrations set table_id=?,table_name=?,schema_state='NOT_PROVISIONED',
                               last_error_code=null,updated_at=now(),version=version+1 where id=?
                        """, tableId, TABLE_NAME, row.id());
            }
            synchronizeFieldMappings(row, tableId, true);
            jdbc.update("""
                    update feishu_bitable_integrations set schema_state='READY',last_error_code=null,
                           updated_at=now(),version=version+1 where id=?
                    """, row.id());
            audit.record(actor, "FEISHU_TABLE_PROVISIONED", "FEISHU_INTEGRATION", row.id().toString(),
                    "SUCCESS", Map.of("fieldCount", FeishuCustomerSchema.FIELDS.size()));
            return new FeishuIntegrationDtos.ProvisionResponse(tableId, TABLE_NAME,
                    FeishuCustomerSchema.FIELDS.size(), "READY");
        } catch (RuntimeException exception) {
            String code = safeError(exception);
            jdbc.update("""
                    update feishu_bitable_integrations set schema_state='UNAVAILABLE',last_error_code=?,updated_at=now()
                    where id=?
                    """, code, row.id());
            audit.record(actor, "FEISHU_TABLE_PROVISIONED", "FEISHU_INTEGRATION", row.id().toString(),
                    "FAILED", Map.of("errorCode", code));
            throw ApiException.unavailable(code, "飞书数据表初始化失败，请检查应用的多维表格管理权限");
        }
    }

    @Transactional
    public FeishuIntegrationDtos.SchemaResponse schema() {
        CurrentActor actor = requireAdmin();
        IntegrationRow row = findByOrganization(actor.organizationId());
        if (row == null || row.tableId() == null) {
            return new FeishuIntegrationDtos.SchemaResponse(false, "NOT_PROVISIONED", List.of(), Instant.now());
        }
        try {
            return inspectSchema(row, true);
        } catch (RuntimeException exception) {
            String code = safeError(exception);
            throw ApiException.unavailable(code, "飞书表结构暂时无法读取，请检查权限后重试");
        }
    }

    @Transactional(readOnly = true)
    public FeishuIntegrationDtos.InitialSyncPreview initialPreview() {
        CurrentActor actor = requireAdmin();
        requireConfiguration(actor.organizationId());
        Map<String, Object> counts = jdbc.queryForMap("""
                select count(*) total,
                       count(*) filter (where visibility='PRIVATE') private_count,
                       count(*) filter (where archived_at is not null) archived_count
                from customers where organization_id=?
                """, actor.organizationId());
        return new FeishuIntegrationDtos.InitialSyncPreview(number(counts.get("total")),
                number(counts.get("private_count")), number(counts.get("archived_count")),
                List.of("手机号", "微信号", "QQ号"),
                "私有客户和完整联系方式将写入同一张飞书多维表格，飞书分享权限决定同步后的可见范围。");
    }

    @Transactional
    public FeishuIntegrationDtos.RunResponse startInitialSync() {
        CurrentActor actor = requireAdmin();
        IntegrationRow row = requireReadyActive(actor.organizationId());
        UUID runId = createFullRun(row, "INITIAL", actor.membershipId());
        audit.record(actor, "FEISHU_INITIAL_SYNC_STARTED", "FEISHU_SYNC_RUN", runId.toString(), "SUCCESS", null);
        return run(runId, actor.organizationId());
    }

    @Transactional
    public FeishuIntegrationDtos.RunResponse syncNow() {
        CurrentActor actor = requireAdmin();
        IntegrationRow row = requireReadyActive(actor.organizationId());
        UUID runId = createFullRun(row, "MANUAL", actor.membershipId());
        audit.record(actor, "FEISHU_MANUAL_SYNC_STARTED", "FEISHU_SYNC_RUN", runId.toString(), "SUCCESS", null);
        return run(runId, actor.organizationId());
    }

    @Transactional
    public FeishuIntegrationDtos.RunResponse reconcile() {
        CurrentActor actor = requireAdmin();
        IntegrationRow row = requireReadyActive(actor.organizationId());
        UUID runId = createFullRun(row, "RECONCILIATION", actor.membershipId());
        audit.record(actor, "FEISHU_RECONCILIATION_STARTED", "FEISHU_SYNC_RUN", runId.toString(), "SUCCESS", null);
        return run(runId, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<FeishuIntegrationDtos.RunResponse> runs(int size) {
        CurrentActor actor = requireAdmin();
        int limit = Math.min(Math.max(size, 1), 200);
        return jdbc.query("""
                select * from feishu_bitable_sync_runs where organization_id=?
                order by created_at desc limit ?
                """, (rs, index) -> new FeishuIntegrationDtos.RunResponse(
                rs.getObject("id", UUID.class), rs.getString("run_type"), rs.getString("status"),
                rs.getInt("total_count"), rs.getInt("success_count"), rs.getInt("failed_count"),
                rs.getInt("skipped_count"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("completed_at")), rs.getString("last_error_code"),
                rs.getTimestamp("created_at").toInstant()), actor.organizationId(), limit);
    }

    @Transactional(readOnly = true)
    public List<FeishuIntegrationDtos.FailureResponse> failures(int size) {
        CurrentActor actor = requireAdmin();
        return jdbc.query("""
                select id,customer_id,attempt_count,last_error_code,next_attempt_at,updated_at
                from feishu_bitable_sync_queue where organization_id=? and status='FAILED'
                order by updated_at desc limit ?
                """, (rs, index) -> new FeishuIntegrationDtos.FailureResponse(
                rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getInt("attempt_count"), rs.getString("last_error_code"),
                rs.getTimestamp("next_attempt_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                actor.organizationId(), Math.min(Math.max(size, 1), 200));
    }

    @Transactional
    public FeishuIntegrationDtos.RunResponse retryFailure(UUID queueId) {
        CurrentActor actor = requireAdmin();
        IntegrationRow row = requireReadyActive(actor.organizationId());
        lockIntegration(row.id());
        UUID runId = activeRunId(row.id());
        boolean createdRun = runId == null;
        if (createdRun) {
            runId = UUID.randomUUID();
            jdbc.update("""
                    insert into feishu_bitable_sync_runs
                    (id,integration_id,organization_id,run_type,status,total_count,created_by_membership_id)
                    values (?,?,?,'MANUAL','PENDING',1,?)
                    """, runId, row.id(), actor.organizationId(), actor.membershipId());
        }
        int changed = jdbc.update("""
                update feishu_bitable_sync_queue set run_id=?,status='PENDING',attempt_count=0,
                       next_attempt_at=now(),last_error_code=null,updated_at=now()
                where id=? and organization_id=? and integration_id=? and status='FAILED'
                """, runId, queueId, actor.organizationId(), row.id());
        if (changed == 0) throw ApiException.conflict("FEISHU_FAILURE_NOT_RETRYABLE", "该失败任务不存在或当前不可重试");
        if (!createdRun) jdbc.update("""
                update feishu_bitable_sync_runs set total_count=total_count+1,updated_at=now() where id=?
                """, runId);
        audit.record(actor, "FEISHU_SYNC_FAILURE_RETRIED", "FEISHU_SYNC_QUEUE", queueId.toString(), "SUCCESS", null);
        return run(runId, actor.organizationId());
    }

    public IntegrationRow findById(UUID integrationId) {
        return jdbc.query("select * from feishu_bitable_integrations where id=?", integrationMapper(), integrationId)
                .stream().findFirst().orElse(null);
    }

    RemoteContext requireRemoteContext(UUID integrationId) {
        IntegrationRow row = findById(integrationId);
        if (row == null || !row.active() || !"READY".equals(row.schemaState()) || row.tableId() == null) {
            throw new FeishuBitableClient.FeishuClientException("FEISHU_INTEGRATION_NOT_READY");
        }
        FeishuIntegrationDtos.SchemaResponse schema = inspectSchema(row, false);
        if (!"READY".equals(schema.state())) {
            jdbc.update("""
                    update feishu_bitable_integrations
                    set active=false,schema_state='MISMATCH',last_error_code='FEISHU_SCHEMA_MISMATCH',
                        updated_at=now(),version=version+1
                    where id=?
                    """, row.id());
            throw new FeishuBitableClient.FeishuClientException("FEISHU_SCHEMA_MISMATCH");
        }
        Map<String, String> names = new LinkedHashMap<>();
        for (FeishuIntegrationDtos.FieldStatus field : schema.fields()) names.put(field.key(), field.remoteName());
        return new RemoteContext(row, credentials(row), names);
    }

    UUID createHourlyRun(IntegrationRow row) {
        lockIntegration(row.id());
        UUID active = activeRunId(row.id());
        if (active != null) return active;
        Integer pending = jdbc.queryForObject("""
                select count(*) from feishu_bitable_sync_queue
                where integration_id=? and run_id is null and status in ('PENDING','RETRYING')
                """, Integer.class, row.id());
        if (pending == null || pending == 0) return null;
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                insert into feishu_bitable_sync_runs
                (id,integration_id,organization_id,run_type,status,total_count)
                values (?,?,?,'HOURLY','PENDING',?)
                """, runId, row.id(), row.organizationId(), pending);
        jdbc.update("""
                update feishu_bitable_sync_queue set run_id=?,updated_at=now()
                where integration_id=? and run_id is null and status in ('PENDING','RETRYING')
                """, runId, row.id());
        return runId;
    }

    UUID createSystemFullRun(IntegrationRow row, String type) {
        return createFullRun(row, type, null);
    }

    List<IntegrationRow> activeIntegrations() {
        return jdbc.query("""
                select * from feishu_bitable_integrations
                where active=true and schema_state='READY' order by id
                """, integrationMapper());
    }

    IntegrationRow findOAuthByOrganization(UUID organizationId) {
        return jdbc.query("""
                select integration.* from feishu_bitable_integrations integration
                join organizations organization on organization.id=integration.organization_id
                where integration.organization_id=? and integration.oauth_enabled=true
                  and organization.status='ACTIVE'
                """, integrationMapper(), organizationId).stream().findFirst().orElse(null);
    }

    IntegrationRow findActiveByOrganization(UUID organizationId) {
        return jdbc.query("""
                select integration.* from feishu_bitable_integrations integration
                join organizations organization on organization.id=integration.organization_id
                where integration.organization_id=? and integration.active=true
                  and organization.status='ACTIVE'
                """, integrationMapper(), organizationId).stream().findFirst().orElse(null);
    }

    IntegrationRow findActiveOAuthById(UUID integrationId) {
        return jdbc.query("""
                select integration.* from feishu_bitable_integrations integration
                join organizations organization on organization.id=integration.organization_id
                where integration.id=? and integration.oauth_enabled=true
                  and organization.status='ACTIVE'
                """, integrationMapper(), integrationId).stream().findFirst().orElse(null);
    }

    List<IntegrationRow> oauthIntegrations() {
        return jdbc.query("""
                select integration.* from feishu_bitable_integrations integration
                join organizations organization on organization.id=integration.organization_id
                where integration.oauth_enabled=true and organization.status='ACTIVE'
                order by integration.created_at
                """, integrationMapper());
    }

    public List<IntegrationRow> inboundIntegrations() {
        return jdbc.query("""
                select * from feishu_bitable_integrations
                where active=true and inbound_sync_enabled=true and schema_state='READY'
                order by id
                """, integrationMapper());
    }

    public List<IntegrationRow> longConnectionIntegrations() {
        return jdbc.query("""
                select integration.* from feishu_bitable_integrations integration
                join organizations organization on organization.id=integration.organization_id
                where organization.status='ACTIVE' and (
                    (integration.active=true and integration.inbound_sync_enabled=true and integration.schema_state='READY')
                    or exists (select 1 from feishu_deal_bot_settings bot
                               where bot.integration_id=integration.id and bot.enabled=true)
                ) order by integration.id
                """, integrationMapper());
    }

    public void updateEventState(UUID integrationId, String state, String errorCode, boolean receivedEvent) {
        jdbc.update("""
                update feishu_bitable_integrations
                set event_state=?,last_event_error_code=?,last_event_at=case when ? then now() else last_event_at end,
                    updated_at=now() where id=?
                """, state, errorCode, receivedEvent, integrationId);
    }

    void markEventSubscriptionAuthorized(UUID integrationId) {
        jdbc.update("""
                update feishu_bitable_integrations
                set event_subscription_authorized_at=now(),event_state='CONNECTING',
                    last_event_error_code=null,updated_at=now(),version=version+1
                where id=?
                """, integrationId);
    }

    public FeishuBitableClient.Credentials credentialsFor(IntegrationRow row) {
        return credentials(row);
    }

    void rememberTenant(UUID integrationId, String tenantKey) {
        int changed = jdbc.update("""
                update feishu_bitable_integrations
                set tenant_key=coalesce(tenant_key,?),updated_at=now()
                where id=? and (tenant_key is null or tenant_key=?)
                """, tenantKey, integrationId, tenantKey);
        if (changed == 0) throw ApiException.conflict("FEISHU_TENANT_MISMATCH", "飞书租户与当前组织配置不一致");
    }

    private FeishuIntegrationDtos.SchemaResponse inspectSchema(IntegrationRow row, boolean persistState) {
        try {
            List<FeishuBitableClient.RemoteField> remote = client.listFields(credentials(row), row.baseAppToken(), row.tableId());
            Map<String, FeishuBitableClient.RemoteField> byId = new HashMap<>();
            remote.forEach(field -> byId.put(field.id(), field));
            Map<String, StoredField> stored = storedFields(row.id());
            List<FeishuIntegrationDtos.FieldStatus> statuses = new ArrayList<>();
            boolean ready = true;
            for (FeishuBitableClient.FieldDefinition expected : FeishuCustomerSchema.FIELDS) {
                StoredField mapping = stored.get(expected.key());
                FeishuBitableClient.RemoteField actual = mapping == null ? null : byId.get(mapping.fieldId());
                boolean present = actual != null;
                boolean compatible = present && actual.type() == expected.type();
                ready &= compatible;
                statuses.add(new FeishuIntegrationDtos.FieldStatus(expected.key(), expected.name(),
                        actual == null ? null : actual.name(), expected.type(), actual == null ? null : actual.type(),
                        present, compatible));
            }
            String state = ready ? "READY" : "MISMATCH";
            if (persistState) jdbc.update("""
                    update feishu_bitable_integrations set schema_state=?,last_error_code=?,updated_at=now()
                    where id=?
                    """, state, ready ? null : "FEISHU_SCHEMA_MISMATCH", row.id());
            return new FeishuIntegrationDtos.SchemaResponse(true, state, statuses, Instant.now());
        } catch (RuntimeException exception) {
            if (persistState) jdbc.update("""
                    update feishu_bitable_integrations set schema_state='UNAVAILABLE',last_error_code=?,updated_at=now()
                    where id=?
                    """, safeError(exception), row.id());
            throw exception;
        }
    }

    private void synchronizeFieldMappings(IntegrationRow row, String tableId, boolean repair) {
        List<FeishuBitableClient.RemoteField> remote = client.listFields(credentials(row), row.baseAppToken(), tableId);
        Map<String, FeishuBitableClient.RemoteField> byName = new HashMap<>();
        Map<String, FeishuBitableClient.RemoteField> byId = new HashMap<>();
        remote.forEach(field -> {
            byName.put(field.name(), field);
            byId.put(field.id(), field);
        });
        Map<String, StoredField> stored = storedFields(row.id());
        for (FeishuBitableClient.FieldDefinition expected : FeishuCustomerSchema.FIELDS) {
            StoredField mapping = stored.get(expected.key());
            FeishuBitableClient.RemoteField actual = mapping == null ? byName.get(expected.name()) : byId.get(mapping.fieldId());
            if (actual == null) actual = byName.get(expected.name());
            if (actual == null && repair) {
                actual = client.createField(credentials(row), row.baseAppToken(), tableId, expected);
            }
            if (actual == null || actual.type() != expected.type()) {
                throw new FeishuBitableClient.FeishuClientException("FEISHU_SCHEMA_MISMATCH");
            }
            jdbc.update("""
                    insert into feishu_bitable_fields
                    (integration_id,logical_key,field_id,expected_name,expected_type)
                    values (?,?,?,?,?)
                    on conflict (integration_id,logical_key) do update set
                        field_id=excluded.field_id,expected_name=excluded.expected_name,
                        expected_type=excluded.expected_type,updated_at=now()
                    """, row.id(), expected.key(), actual.id(), expected.name(), expected.type());
        }
    }

    private Map<String, StoredField> storedFields(UUID integrationId) {
        Map<String, StoredField> result = new HashMap<>();
        jdbc.query("""
                select logical_key,field_id,expected_name,expected_type
                from feishu_bitable_fields where integration_id=?
                """, (RowCallbackHandler) rs -> result.put(rs.getString("logical_key"), new StoredField(
                rs.getString("field_id"), rs.getString("expected_name"), rs.getInt("expected_type"))), integrationId);
        return result;
    }

    private UUID createFullRun(IntegrationRow row, String type, UUID actorMembershipId) {
        lockIntegration(row.id());
        UUID active = activeRunId(row.id());
        if (active != null) return active;
        UUID runId = UUID.randomUUID();
        Integer total = jdbc.queryForObject("select count(*) from customers where organization_id=?",
                Integer.class, row.organizationId());
        jdbc.update("""
                insert into feishu_bitable_sync_runs
                (id,integration_id,organization_id,run_type,status,total_count,created_by_membership_id)
                values (?,?,?,?, 'PENDING', ?, ?)
                """, runId, row.id(), row.organizationId(), type, total == null ? 0 : total, actorMembershipId);
        jdbc.update("""
                insert into feishu_bitable_sync_queue
                (id,integration_id,organization_id,customer_id,run_id,generation,status,attempt_count,next_attempt_at)
                select gen_random_uuid(),?,?,customer.id,?,1,'PENDING',0,now()
                from customers customer where customer.organization_id=?
                on conflict (integration_id,customer_id) do update set
                    run_id=excluded.run_id,generation=feishu_bitable_sync_queue.generation+1,
                    status='PENDING',attempt_count=0,next_attempt_at=now(),last_error_code=null,updated_at=now()
                """, row.id(), row.organizationId(), runId, row.organizationId());
        return runId;
    }

    private UUID activeRunId(UUID integrationId) {
        return jdbc.query("""
                select id from feishu_bitable_sync_runs
                where integration_id=? and status in ('PENDING','RUNNING')
                order by created_at limit 1
                """, (rs, index) -> rs.getObject("id", UUID.class), integrationId)
                .stream().findFirst().orElse(null);
    }

    private void lockIntegration(UUID integrationId) {
        jdbc.queryForObject("select id from feishu_bitable_integrations where id=? for update", UUID.class,
                integrationId);
    }

    private FeishuIntegrationDtos.RunResponse run(UUID id, UUID organizationId) {
        return jdbc.query("select * from feishu_bitable_sync_runs where id=? and organization_id=?",
                (rs, index) -> new FeishuIntegrationDtos.RunResponse(
                        rs.getObject("id", UUID.class), rs.getString("run_type"), rs.getString("status"),
                        rs.getInt("total_count"), rs.getInt("success_count"), rs.getInt("failed_count"),
                        rs.getInt("skipped_count"), instant(rs.getTimestamp("started_at")),
                        instant(rs.getTimestamp("completed_at")), rs.getString("last_error_code"),
                        rs.getTimestamp("created_at").toInstant()), id, organizationId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("FEISHU_SYNC_RUN_NOT_FOUND", "同步任务不存在"));
    }

    private IntegrationRow requireReadyActive(UUID organizationId) {
        IntegrationRow row = requireConfiguration(organizationId);
        if (!row.active() || !"READY".equals(row.schemaState()) || row.tableId() == null) {
            throw ApiException.conflict("FEISHU_INTEGRATION_NOT_READY", "请先初始化并启用飞书同步");
        }
        return row;
    }

    private IntegrationRow requireConfiguration(UUID organizationId) {
        IntegrationRow row = findByOrganization(organizationId);
        if (row == null) throw ApiException.notFound("FEISHU_INTEGRATION_NOT_CONFIGURED", "尚未配置飞书多维表格同步");
        return row;
    }

    private IntegrationRow findByOrganization(UUID organizationId) {
        return jdbc.query("select * from feishu_bitable_integrations where organization_id=?",
                integrationMapper(), organizationId).stream().findFirst().orElse(null);
    }

    private org.springframework.jdbc.core.RowMapper<IntegrationRow> integrationMapper() {
        return (rs, index) -> new IntegrationRow(
                rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("app_id"), rs.getBytes("encrypted_app_secret"), rs.getString("base_app_token"),
                rs.getString("table_id"), rs.getString("table_name"), rs.getBoolean("active"),
                rs.getBoolean("oauth_enabled"), rs.getBoolean("inbound_sync_enabled"),
                rs.getString("schema_state"), rs.getString("tenant_key"), rs.getString("event_state"),
                instant(rs.getTimestamp("last_event_at")), rs.getString("last_event_error_code"),
                instant(rs.getTimestamp("event_subscription_authorized_at")),
                instant(rs.getTimestamp("risk_acknowledged_at")),
                instant(rs.getTimestamp("last_tested_at")), instant(rs.getTimestamp("last_success_at")),
                rs.getString("last_error_code"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private FeishuIntegrationDtos.ConfigurationResponse response(IntegrationRow row) {
        if (row == null) return null;
        Map<String, Object> queue = jdbc.queryForMap("""
                select count(*) filter (where status in ('PENDING','SENDING','RETRYING')) pending_count,
                       count(*) filter (where status='FAILED') failed_count
                from feishu_bitable_sync_queue where integration_id=?
                """, row.id());
        return new FeishuIntegrationDtos.ConfigurationResponse(row.id(), row.appId(), true, row.baseAppToken(),
                row.tableId(), row.tableName(), row.active(), row.oauthEnabled(), row.inboundSyncEnabled(),
                row.schemaState(), row.eventState(), row.lastEventAt(), row.lastEventErrorCode(), row.riskAcknowledgedAt(),
                row.lastTestedAt(), row.lastSuccessAt(), row.lastErrorCode(), number(queue.get("pending_count")),
                number(queue.get("failed_count")), row.version(), row.createdAt(), row.updatedAt());
    }

    private FeishuBitableClient.Credentials credentials(IntegrationRow row) {
        return new FeishuBitableClient.Credentials(row.id(), row.appId(), cipher.decrypt(row.encryptedSecret()));
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return actor;
    }

    private String identifier(String value, int max, String code, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.length() > max || !clean.matches("[A-Za-z0-9_-]{3," + max + "}")) {
            throw ApiException.badRequest(code, message);
        }
        return clean;
    }

    private String cleanSecret(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        if (clean.length() < 8 || clean.length() > 512) {
            throw ApiException.badRequest("FEISHU_APP_SECRET_INVALID", "飞书 App Secret 格式无效");
        }
        return clean;
    }

    String safeError(RuntimeException exception) {
        if (exception instanceof ApiException apiError) return apiError.code();
        if (exception instanceof FeishuBitableClient.FeishuClientException clientError) return clientError.code();
        if (exception instanceof ResourceAccessException) return "FEISHU_NETWORK_ERROR";
        if (exception instanceof HttpStatusCodeException http) return "FEISHU_HTTP_" + http.getStatusCode().value();
        return "FEISHU_REQUEST_REJECTED";
    }

    private long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record IntegrationRow(UUID id, UUID organizationId, String appId, byte[] encryptedSecret,
                          String baseAppToken, String tableId, String tableName, boolean active,
                          boolean oauthEnabled, boolean inboundSyncEnabled,
                          String schemaState, String tenantKey, String eventState,
                          Instant lastEventAt, String lastEventErrorCode,
                          Instant eventSubscriptionAuthorizedAt,
                          Instant riskAcknowledgedAt, Instant lastTestedAt,
                          Instant lastSuccessAt, String lastErrorCode, long version,
                          Instant createdAt, Instant updatedAt) {
        IntegrationRow(UUID id, UUID organizationId, String appId, byte[] encryptedSecret,
                       String baseAppToken, String tableId, String tableName, boolean active,
                       String schemaState, Instant riskAcknowledgedAt, Instant lastTestedAt,
                       Instant lastSuccessAt, String lastErrorCode, long version,
                       Instant createdAt, Instant updatedAt) {
            this(id, organizationId, appId, encryptedSecret, baseAppToken, tableId, tableName, active,
                    false, false, schemaState, null, "DISABLED", null, null, null, riskAcknowledgedAt,
                    lastTestedAt, lastSuccessAt, lastErrorCode, version, createdAt, updatedAt);
        }
    }

    record RemoteContext(IntegrationRow integration, FeishuBitableClient.Credentials credentials,
                         Map<String, String> remoteNames) {}

    private record StoredField(String fieldId, String name, int type) {}
}
