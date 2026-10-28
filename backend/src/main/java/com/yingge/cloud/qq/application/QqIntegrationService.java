package com.yingge.cloud.qq.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yingge.cloud.assistant.api.AssistantDtos;
import com.yingge.cloud.assistant.application.AssistantFacade;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.interaction.api.InteractionDtos;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.qq.api.QqDtos;
import com.yingge.cloud.qq.infrastructure.QqBridgeClient;
import com.yingge.cloud.qq.infrastructure.QqDataCipher;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class QqIntegrationService {
    private static final Logger log = LoggerFactory.getLogger(QqIntegrationService.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentActorProvider actors;
    private final CustomerService customers;
    private final InteractionService interactions;
    private final AssistantFacade assistant;
    private final QqBridgeClient bridge;
    private final QqDataCipher cipher;
    private final QqProperties properties;
    private final AuditService audit;

    public QqIntegrationService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                CurrentActorProvider actors, CustomerService customers,
                                InteractionService interactions, AssistantFacade assistant,
                                QqBridgeClient bridge, QqDataCipher cipher, QqProperties properties,
                                AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.actors = actors;
        this.customers = customers;
        this.interactions = interactions;
        this.assistant = assistant;
        this.bridge = bridge;
        this.cipher = cipher;
        this.properties = properties;
        this.audit = audit;
    }

    @Transactional
    public QqDtos.Connection createConnection() {
        CurrentActor actor = requireWritable();
        requireEnabled(actor.organizationId());
        if (!cipher.configured() || !bridge.configured()) {
            throw ApiException.unavailable("QQ_INTEGRATION_NOT_CONFIGURED", "QQ 接入尚未完成服务器配置");
        }
        List<UUID> active = jdbc.queryForList("""
                select id from qq_connections where organization_id=:organizationId
                  and membership_id=:membershipId and revoked_at is null
                """, params("organizationId", actor.organizationId(), "membershipId", actor.membershipId()), UUID.class);
        if (!active.isEmpty()) return refresh(active.getFirst(), actor);
        JsonNode created = bridge.create(actor.organizationId() + ":" + actor.membershipId());
        String instanceId = text(created, "instanceId");
        if (instanceId == null) throw ApiException.unavailable("QQ_BRIDGE_INVALID_RESPONSE", "QQ Bridge 返回无效实例");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into qq_connections
                  (id, organization_id, membership_id, bridge_instance_id, status)
                values (:id, :organizationId, :membershipId, :instanceId, 'PAIRING')
                """, params("id", id, "organizationId", actor.organizationId(),
                "membershipId", actor.membershipId(), "instanceId", instanceId));
        audit.record(actor, "QQ_CONNECTION_CREATED", "QQ_CONNECTION", id.toString(), "SUCCESS", null);
        return refresh(id, actor);
    }

    @Transactional
    public QqDtos.Connection myConnection() {
        CurrentActor actor = requireWritable();
        List<UUID> active = jdbc.queryForList("""
                select id from qq_connections where organization_id=:organizationId
                  and membership_id=:membershipId and revoked_at is null
                """, params("organizationId", actor.organizationId(), "membershipId", actor.membershipId()), UUID.class);
        return active.isEmpty() ? null : refresh(active.getFirst(), actor);
    }

    @Transactional
    public void revokeMine() {
        CurrentActor actor = requireWritable();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, bridge_instance_id from qq_connections where organization_id=:organizationId
                  and membership_id=:membershipId and revoked_at is null
                """, params("organizationId", actor.organizationId(), "membershipId", actor.membershipId()));
        if (rows.isEmpty()) return;
        String instanceId = (String) rows.getFirst().get("bridge_instance_id");
        if (instanceId != null) bridge.revoke(instanceId);
        jdbc.update("""
                update qq_connections set status='REVOKED', encrypted_access_token=null,
                  revoked_at=now(), updated_at=now(), version=version+1 where id=:id
                """, params("id", rows.getFirst().get("id")));
        audit.record(actor, "QQ_CONNECTION_REVOKED", "QQ_CONNECTION", rows.getFirst().get("id").toString(),
                "SUCCESS", null);
    }

    @Transactional
    public QqDtos.FriendPage friends(int page, int limit) {
        CurrentActor actor = requireWritable();
        ConnectionRow connection = requireConnected(actor);
        JsonNode response = bridge.friends(connection.instanceId(), Math.max(1, page), Math.min(Math.max(limit, 1), 200));
        JsonNode data = response.has("data") ? response.get("data") : response;
        JsonNode items = data.path("friends");
        List<QqDtos.Friend> friends = new ArrayList<>();
        if (items.isArray()) {
            items.forEach(item -> friends.add(new QqDtos.Friend(
                    text(item, "uid"), text(item, "uin"), text(item, "nick"), text(item, "remark"))));
        }
        return new QqDtos.FriendPage(friends, data.path("currentPage").asInt(page),
                data.path("totalPages").asInt(1), data.path("totalCount").asLong(friends.size()));
    }

    @Transactional
    public QqDtos.WorkbenchBootstrap workbenchBootstrap() {
        CurrentActor actor = requireWritable();
        requireEnabled(actor.organizationId());
        ConnectionRow connection = requireConnected(actor, true);
        return new QqDtos.WorkbenchBootstrap(connection.status(), connection.maskedQq(), "/qq-workbench/",
                "/api/v1/qq-workbench/ws", properties.retentionDays(), 10L * 1024 * 1024 * 1024);
    }

    @Transactional(readOnly = true)
    public WorkbenchSession requireWorkbenchSession(CurrentActor actor) {
        requireWritable(actor);
        requireEnabled(actor.organizationId());
        ConnectionRow connection = requireConnected(actor, false);
        return new WorkbenchSession(connection.id(), connection.instanceId(), actor.membershipId(), actor.organizationId());
    }

    public WorkbenchSession requireWorkbenchSession() {
        return requireWorkbenchSession(requireWritable());
    }

    public void auditWorkbench(WorkbenchSession session, String method, String capability, boolean write, boolean download) {
        if (!write && !download) return;
        CurrentActor actor = requireWritable();
        if (!actor.membershipId().equals(session.membershipId()) || !actor.organizationId().equals(session.organizationId())) {
            throw ApiException.forbidden("QQ 工作台会话与当前登录身份不一致");
        }
        audit.record(actor, download ? "QQ_WORKBENCH_DOWNLOAD" : "QQ_WORKBENCH_WRITE",
                "QQ_CONNECTION", session.connectionId().toString(), "SUCCESS",
                Map.of("method", method, "capability", capability));
    }

    @Transactional
    public QqDtos.Preview preview(QqDtos.PreviewRequest body) {
        CurrentActor actor = requireWritable();
        requireEnabled(actor.organizationId());
        var customer = customers.requireVisibleEntity(body.customerId(), actor);
        customers.requireTimelineEditable(customer, actor);
        validateRange(body.rangeStart(), body.rangeEnd());
        boolean groupChat = body.chatType() == 2;
        String peerQq = TextNormalizer.contact(body.peerQq());
        String targetQq = TextNormalizer.contact(body.targetSenderQq());
        if (!groupChat && targetQq == null) targetQq = peerQq;
        if (peerQq == null) throw ApiException.badRequest("QQ_PEER_REQUIRED", "请选择有效 QQ 会话");
        if (groupChat && (!body.groupContextConfirmed() || TextNormalizer.optional(body.targetSenderUid()) == null
                || targetQq == null)) {
            throw ApiException.badRequest("QQ_GROUP_CONTEXT_CONFIRMATION_REQUIRED",
                    "群聊入档必须选择目标成员并确认第三方对话的业务必要性");
        }
        if (targetQq == null) throw ApiException.badRequest("QQ_TARGET_REQUIRED", "请选择有效的目标客户 QQ");
        String customerQq = TextNormalizer.contact(customer.getQqId());
        Integer duplicate = jdbc.queryForObject("""
                select count(*) from customers where organization_id=:organizationId
                  and id<>:customerId and normalized_qq_id=:qq
                """, params("organizationId", actor.organizationId(), "customerId", customer.getId(),
                "qq", targetQq), Integer.class);
        if (duplicate != null && duplicate > 0) {
            throw ApiException.conflict("QQ_MULTIPLE_CUSTOMERS", "该 QQ 对应多份客户档案，请先处理重复档案再导入");
        }
        if (customerQq == null) {
            if (!body.bindMissingQq()) {
                throw ApiException.conflict("CUSTOMER_QQ_BINDING_REQUIRED", "客户尚未填写 QQ 号，请确认绑定后再导入");
            }
            jdbc.update("""
                    update customers set qq_id=:qq, normalized_qq_id=:qq,
                      updated_at=now(), version=version+1 where id=:customerId and organization_id=:organizationId
                    """, params("qq", targetQq, "customerId", customer.getId(),
                    "organizationId", actor.organizationId()));
        } else if (!customerQq.equals(targetQq)) {
            throw ApiException.conflict("CUSTOMER_QQ_MISMATCH", "所选目标成员与客户档案 QQ 号不一致，请先修正客户档案");
        }
        ConnectionRow connection = requireConnected(actor);
        ArrayNode allMessages = objectMapper.createArrayNode();
        int page = 1;
        int pageSize = 2000;
        boolean hasNext;
        do {
            int previousSize = allMessages.size();
            JsonNode response = bridge.messages(connection.instanceId(), body.chatType(), body.peerUid(), body.rangeStart().toEpochMilli(),
                    body.rangeEnd().toEpochMilli(), page, pageSize);
            JsonNode data = response.has("data") ? response.get("data") : response;
            JsonNode messages = data.path("messages");
            if (messages.isArray()) messages.forEach(allMessages::add);
            hasNext = data.path("hasNext").asBoolean(false);
            if (hasNext && allMessages.size() == previousSize) {
                throw ApiException.unavailable("QQ_MESSAGE_PAGING_STALLED", "QQ 消息分页未继续推进，请刷新会话后重试");
            }
            page++;
        } while (hasNext && allMessages.size() < Math.max(1, properties.maxMessagesPerImport()));
        if (allMessages.isEmpty()) throw ApiException.badRequest("QQ_MESSAGES_EMPTY", "所选时间范围内没有可导入的 QQ 消息");
        requireCompleteImport(hasNext, allMessages.size(), properties.maxMessagesPerImport());
        byte[] raw = bytes(allMessages);
        String hash = sha256(raw);
        UUID importId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(Math.max(1, properties.retentionDays()), ChronoUnit.DAYS);
        Path relative = Path.of(actor.organizationId().toString(), importId + ".qce");
        writeEncrypted(relative, raw);
        AssistantDtos.QqConversationDraft draft;
        try {
            draft = assistant.summarizeQqConversation(customer.getId(), aiText(allMessages, body.chatType(),
                    body.targetSenderUid(), targetQq, body.peerUid()));
        } catch (ApiException exception) {
            draft = emptyDraft("智能总结暂不可用，请人工填写后确认：" + exception.getMessage());
        }
        try {
            jdbc.update("""
                    insert into qq_imports
                      (id, organization_id, membership_id, customer_id, connection_id, chat_type, peer_uid, peer_qq,
                       session_name, target_sender_uid, target_sender_qq, group_context_confirmed,
                       range_start, range_end, message_count, message_hash, encrypted_payload_path,
                       expires_at, draft)
                    values (:id, :organizationId, :membershipId, :customerId, :connectionId, :chatType, :peerUid, :peerQq,
                       :sessionName, :targetSenderUid, :targetSenderQq, :groupContextConfirmed,
                       :rangeStart, :rangeEnd, :messageCount, :messageHash, :path, :expiresAt,
                       cast(:draft as jsonb))
                    """, params("id", importId, "organizationId", actor.organizationId(),
                    "membershipId", actor.membershipId(), "customerId", customer.getId(),
                    "connectionId", connection.id(), "chatType", body.chatType(), "peerUid", body.peerUid(),
                    "peerQq", peerQq, "sessionName", TextNormalizer.optional(body.sessionName()),
                    "targetSenderUid", groupChat ? TextNormalizer.optional(body.targetSenderUid()) : body.peerUid(),
                    "targetSenderQq", targetQq, "groupContextConfirmed", groupChat && body.groupContextConfirmed(),
                    "rangeStart", Timestamp.from(body.rangeStart()), "rangeEnd", Timestamp.from(body.rangeEnd()),
                    "messageCount", allMessages.size(), "messageHash", hash,
                    "path", relative.toString(), "expiresAt", Timestamp.from(expiresAt), "draft", json(draft)));
        } catch (RuntimeException exception) {
            deleteQuietly(relative);
            if (isUniqueViolation(exception)) {
                throw ApiException.conflict("QQ_IMPORT_DUPLICATE", "该段 QQ 消息已经导入过，请勿重复提交");
            }
            throw exception;
        }
        audit.record(actor, "QQ_IMPORT_PREVIEWED", "QQ_IMPORT", importId.toString(), "SUCCESS",
                Map.of("customerId", customer.getId().toString(), "messageCount", allMessages.size()));
        return new QqDtos.Preview(importId, customer.getId(), body.chatType(), body.peerUid(), peerQq,
                TextNormalizer.optional(body.sessionName()), groupChat ? body.targetSenderUid() : body.peerUid(),
                targetQq, groupChat && body.groupContextConfirmed(), allMessages.size(),
                body.rangeStart(), body.rangeEnd(), expiresAt, draft, allMessages);
    }

    @Transactional
    public QqDtos.ConfirmResult confirm(UUID importId, QqDtos.ConfirmRequest body) {
        CurrentActor actor = requireWritable();
        ImportRow row = requireImport(importId, actor, true);
        if ("CONFIRMED".equals(row.status())) {
            return new QqDtos.ConfirmResult(importId, row.interactionId(), "CONFIRMED");
        }
        if (row.expiresAt().isBefore(Instant.now())) throw ApiException.conflict("QQ_IMPORT_EXPIRED", "QQ 对话预览已过期");
        AssistantDtos.QqConversationDraft draft = body.draft();
        if (TextNormalizer.optional(draft.summary()) == null) {
            throw ApiException.badRequest("QQ_SUMMARY_REQUIRED", "确认导入前必须填写对话摘要");
        }
        String idempotencyHash = sha256(body.idempotencyKey().getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id, interaction_id from qq_imports
                where organization_id=:organizationId and membership_id=:membershipId
                  and idempotency_key_hash=:keyHash
                """, params("organizationId", actor.organizationId(), "membershipId", actor.membershipId(),
                "keyHash", idempotencyHash));
        if (!existing.isEmpty()) {
            UUID existingId = (UUID) existing.getFirst().get("id");
            if (!existingId.equals(importId)) {
                throw ApiException.conflict("QQ_IDEMPOTENCY_KEY_REUSED", "该幂等键已用于另一条 QQ 导入");
            }
            UUID interactionId = (UUID) existing.getFirst().get("interaction_id");
            if (interactionId != null) return new QqDtos.ConfirmResult(importId, interactionId, "CONFIRMED");
        }
        String details = formatDetails(row, draft);
        InteractionDtos.Response interaction = interactions.create(row.customerId(), new InteractionDtos.CreateRequest(
                row.rangeEnd(), "QQ导入", draft.summary().trim(), details,
                TextNormalizer.optional(draft.outcome()), TextNormalizer.optional(draft.nextStep()),
                draft.nextFollowUpAt(), null, null, null, null));
        int changed = jdbc.update("""
                update qq_imports set status='CONFIRMED', draft=cast(:draft as jsonb),
                  idempotency_key_hash=:keyHash,
                  interaction_id=:interactionId, confirmed_at=now()
                where id=:id and organization_id=:organizationId and status='PREVIEWED'
                """, params("draft", json(draft), "keyHash", idempotencyHash,
                "interactionId", interaction.id(), "id", importId,
                "organizationId", actor.organizationId()));
        if (changed == 0) throw ApiException.conflict("QQ_IMPORT_ALREADY_CONFIRMED", "该 QQ 对话已被确认");
        audit.record(actor, "QQ_IMPORT_CONFIRMED", "QQ_IMPORT", importId.toString(), "SUCCESS",
                Map.of("interactionId", interaction.id().toString(), "idempotencyKeyHash",
                        idempotencyHash));
        return new QqDtos.ConfirmResult(importId, interaction.id(), "CONFIRMED");
    }

    @Transactional(readOnly = true)
    public QqDtos.Preview raw(UUID importId) {
        CurrentActor actor = actors.require();
        ImportRow row = requireImport(importId, actor, false);
        requireRawAccess(row, actor);
        if (row.expiresAt().isBefore(Instant.now())) throw ApiException.notFound("QQ_IMPORT_RAW_EXPIRED", "QQ 对话原文已按留存策略清除");
        try {
            Path relative = Path.of(row.path());
            Path target = retentionRoot().resolve(relative).normalize();
            if (!target.startsWith(retentionRoot())) {
                throw ApiException.notFound("QQ_IMPORT_RAW_NOT_FOUND", "QQ 对话原文不存在");
            }
            byte[] decrypted = cipher.decrypt(Files.readAllBytes(target));
            JsonNode messages = objectMapper.readTree(decrypted);
            AssistantDtos.QqConversationDraft draft = objectMapper.readValue(row.draftJson(), AssistantDtos.QqConversationDraft.class);
            return new QqDtos.Preview(row.id(), row.customerId(), row.chatType(), row.peerUid(), row.peerQq(),
                    row.sessionName(), row.targetSenderUid(), row.targetSenderQq(), row.groupContextConfirmed(), row.messageCount(),
                    row.rangeStart(), row.rangeEnd(), row.expiresAt(), draft, messages);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiException.unavailable("QQ_RAW_READ_FAILED", "QQ 对话原文暂时无法读取");
        }
    }

    @Transactional(readOnly = true)
    public List<QqDtos.AdminConnection> adminConnections() {
        CurrentActor actor = requireAdmin();
        return jdbc.query("""
                select connection.id, connection.membership_id, account.display_name, connection.status,
                  connection.masked_qq, connection.last_heartbeat_at, connection.created_at
                from qq_connections connection join memberships membership on membership.id=connection.membership_id
                join user_accounts account on account.id=membership.user_id
                where connection.organization_id=:organizationId and connection.revoked_at is null
                order by connection.created_at desc
                """, params("organizationId", actor.organizationId()), (row, index) -> new QqDtos.AdminConnection(
                row.getObject("id", UUID.class), row.getObject("membership_id", UUID.class),
                row.getString("display_name"), row.getString("status"), row.getString("masked_qq"),
                instant(row, "last_heartbeat_at"), instant(row, "created_at")));
    }

    @Transactional
    public void revokeConnection(UUID connectionId) {
        CurrentActor actor = requireAdmin();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, bridge_instance_id from qq_connections
                where id=:id and organization_id=:organizationId and revoked_at is null
                """, params("id", connectionId, "organizationId", actor.organizationId()));
        if (rows.isEmpty()) {
            throw ApiException.notFound("QQ_CONNECTION_NOT_FOUND", "QQ 连接不存在");
        }
        String instanceId = (String) rows.getFirst().get("bridge_instance_id");
        if (instanceId != null) bridge.revoke(instanceId);
        jdbc.update("""
                update qq_connections set status='REVOKED', encrypted_access_token=null,
                  revoked_at=now(), updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId
                """, params("id", connectionId, "organizationId", actor.organizationId()));
        audit.record(actor, "QQ_CONNECTION_ADMIN_REVOKED", "QQ_CONNECTION", connectionId.toString(),
                "SUCCESS", null);
    }

    @Scheduled(cron = "0 20 2 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void purgeExpiredRawChats() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, encrypted_payload_path from qq_imports
                where expires_at<=now() and status in ('PREVIEWED','CONFIRMED')
                """, Map.of());
        for (Map<String, Object> row : rows) {
            deleteQuietly(Path.of((String) row.get("encrypted_payload_path")));
            jdbc.update("update qq_imports set status='EXPIRED' where id=:id", params("id", row.get("id")));
        }
    }

    private QqDtos.Connection refresh(UUID id, CurrentActor actor) {
        ConnectionRow row = requireConnection(id, actor.organizationId(), actor.membershipId());
        JsonNode status = bridgeStatusWithRecovery(bridge, row.instanceId(),
                actor.organizationId() + ":" + actor.membershipId());
        String state = text(status, "status");
        String maskedQq = text(status, "maskedQq");
        jdbc.update("""
                update qq_connections set status=:status,
                  masked_qq=coalesce(:maskedQq, masked_qq), last_heartbeat_at=now(),
                  updated_at=now(), version=version+1 where id=:id
                """, params("status", state == null ? "ERROR" : state,
                "maskedQq", maskedQq, "id", id));
        return new QqDtos.Connection(id, state == null ? "ERROR" : state,
                maskedQq == null ? row.maskedQq() : maskedQq,
                text(status, "qrCodeDataUrl"), Instant.now(), row.createdAt());
    }

    static JsonNode bridgeStatusWithRecovery(QqBridgeClient bridge, String instanceId, String instanceKey) {
        try {
            return bridge.status(instanceId);
        } catch (ApiException originalFailure) {
            // A host restart or an interrupted container replacement can leave the
            // durable CRM connection row in place after its isolated QCE container
            // disappeared. Re-creating by the same member-scoped key is idempotent
            // and keeps the existing named volumes while producing a fresh QR when
            // the QQ session itself cannot be restored.
            JsonNode recreated = bridge.create(instanceKey);
            String recreatedId = recreated == null ? null : recreated.path("instanceId").asText(null);
            if (!instanceId.equals(recreatedId)) {
                throw ApiException.unavailable("QQ_INSTANCE_RECOVERY_MISMATCH",
                        "QQ 实例恢复校验失败，请稍后重试");
            }
            return bridge.status(instanceId);
        }
    }

    private ConnectionRow requireConnected(CurrentActor actor) { return requireConnected(actor, true); }

    private ConnectionRow requireConnected(CurrentActor actor, boolean refreshStatus) {
        List<UUID> ids = jdbc.queryForList("""
                select id from qq_connections where organization_id=:organizationId
                  and membership_id=:membershipId and revoked_at is null
                """, params("organizationId", actor.organizationId(), "membershipId", actor.membershipId()), UUID.class);
        if (ids.isEmpty()) throw ApiException.conflict("QQ_CONNECTION_REQUIRED", "请先扫码连接工作 QQ");
        if (refreshStatus) refresh(ids.getFirst(), actor);
        ConnectionRow row = requireConnection(ids.getFirst(), actor.organizationId(), actor.membershipId());
        if (!"CONNECTED".equals(row.status())) throw ApiException.conflict("QQ_NOT_CONNECTED", "工作 QQ 尚未完成连接");
        return row;
    }

    private ConnectionRow requireConnection(UUID id, UUID organizationId, UUID membershipId) {
        List<ConnectionRow> rows = jdbc.query("""
                select id, bridge_instance_id, status, masked_qq, created_at from qq_connections
                where id=:id and organization_id=:organizationId and membership_id=:membershipId and revoked_at is null
                """, params("id", id, "organizationId", organizationId, "membershipId", membershipId),
                (row, index) -> new ConnectionRow(row.getObject("id", UUID.class), row.getString("bridge_instance_id"),
                        row.getString("status"), row.getString("masked_qq"), instant(row, "created_at")));
        if (rows.isEmpty()) throw ApiException.notFound("QQ_CONNECTION_NOT_FOUND", "QQ 连接不存在");
        return rows.getFirst();
    }

    private ImportRow requireImport(UUID id, CurrentActor actor, boolean ownerOnly) {
        String ownerScope = ownerOnly ? " and membership_id=:membershipId" : "";
        List<ImportRow> rows = jdbc.query("""
                select id, membership_id, customer_id, chat_type, peer_uid, peer_qq, session_name,
                  target_sender_uid, target_sender_qq, group_context_confirmed, range_start, range_end,
                  message_count, encrypted_payload_path, expires_at, status, draft::text, interaction_id
                from qq_imports where id=:id and organization_id=:organizationId
                """ + ownerScope, params("id", id, "organizationId", actor.organizationId(),
                "membershipId", actor.membershipId()), (row, index) -> new ImportRow(
                row.getObject("id", UUID.class), row.getObject("membership_id", UUID.class),
                row.getObject("customer_id", UUID.class), row.getInt("chat_type"), row.getString("peer_uid"),
                row.getString("peer_qq"), row.getString("session_name"), row.getString("target_sender_uid"),
                row.getString("target_sender_qq"), row.getBoolean("group_context_confirmed"),
                instant(row, "range_start"), instant(row, "range_end"), row.getInt("message_count"),
                row.getString("encrypted_payload_path"), instant(row, "expires_at"), row.getString("status"),
                row.getString("draft"), row.getObject("interaction_id", UUID.class)));
        if (rows.isEmpty()) throw ApiException.notFound("QQ_IMPORT_NOT_FOUND", "QQ 导入记录不存在");
        return rows.getFirst();
    }

    private void requireRawAccess(ImportRow row, CurrentActor actor) {
        if (actor.isAdmin() || row.membershipId().equals(actor.membershipId())) return;
        Boolean allowed = jdbc.queryForObject("""
                select exists(select 1 from customers customer where customer.id=:customerId
                  and customer.organization_id=:organizationId and (
                    customer.owner_membership_id=:membershipId or exists(select 1 from customer_access_grants grant_row
                      where grant_row.customer_id=customer.id and grant_row.grantee_membership_id=:membershipId
                        and grant_row.can_edit=true)))
                """, params("customerId", row.customerId(), "organizationId", actor.organizationId(),
                "membershipId", actor.membershipId()), Boolean.class);
        if (!Boolean.TRUE.equals(allowed)) throw ApiException.forbidden("无权查看该客户的 QQ 对话原文");
    }

    private void requireEnabled(UUID organizationId) {
        Boolean enabled = jdbc.queryForObject("""
                select qq_enabled from organization_follow_up_policies where organization_id=:organizationId
                """, params("organizationId", organizationId), Boolean.class);
        if (!Boolean.TRUE.equals(enabled)) throw ApiException.forbidden("组织管理员尚未启用 QQ 对话接入");
    }

    private CurrentActor requireWritable() { return requireWritable(actors.require()); }
    private CurrentActor requireWritable(CurrentActor actor) {
        if (!actor.canWriteCustomers()) throw ApiException.forbidden("只读账号不能接入或导入 QQ 对话");
        Boolean active = jdbc.queryForObject("""
                select exists(select 1 from memberships membership
                  join user_accounts account on account.id=membership.user_id
                  where membership.id=:membershipId and membership.organization_id=:organizationId
                    and membership.status='ACTIVE' and account.enabled=true)
                """, params("membershipId", actor.membershipId(), "organizationId", actor.organizationId()), Boolean.class);
        if (!Boolean.TRUE.equals(active)) throw ApiException.forbidden("当前成员已停用，QQ 工作台授权失效");
        return actor;
    }
    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin()) throw ApiException.forbidden("只有组织管理员可以查看 QQ 连接状态");
        return actor;
    }

    private void validateRange(Instant start, Instant end) {
        if (!end.isAfter(start)) throw ApiException.badRequest("QQ_RANGE_INVALID", "结束时间必须晚于开始时间");
        if (Duration.between(start, end).toDays() > 90) {
            throw ApiException.badRequest("QQ_RANGE_TOO_LARGE", "单次导入时间范围不能超过 90 天");
        }
        if (end.isAfter(Instant.now().plus(5, ChronoUnit.MINUTES))) {
            throw ApiException.badRequest("QQ_RANGE_IN_FUTURE", "结束时间不能晚于当前时间");
        }
    }

    static void requireCompleteImport(boolean hasNext, int messageCount, int maximum) {
        if (hasNext || messageCount > maximum) {
            throw ApiException.badRequest("QQ_IMPORT_TOO_LARGE", "单次最多导入 " + maximum + " 条消息");
        }
    }

    private String aiText(ArrayNode messages, int chatType, String targetSenderUid, String targetSenderQq, String peerUid) {
        StringBuilder result = new StringBuilder();
        for (JsonNode message : messages) {
            String time = firstText(message, "time", "timestamp", "msgTime");
            JsonNode sender = message.path("sender");
            String senderName = firstText(sender, "name", "nickname", "remark", "uin");
            String senderUid = firstText(message, "senderUid", "senderUin");
            if (senderUid == null) senderUid = firstText(sender, "uid", "uin");
            boolean target = senderUid != null && (senderUid.equals(targetSenderUid) || senderUid.equals(targetSenderQq)
                    || (chatType != 2 && senderUid.equals(peerUid)));
            String evidenceRole = target ? "目标客户" : chatType == 2 ? "群聊上下文" : "销售/其他";
            String content = messageContent(message);
            result.append('[').append(evidenceRole).append("] [").append(time == null ? "时间未知" : time).append("] ")
                    .append(senderName == null ? "发送人未知" : senderName).append(": ")
                    .append(content == null ? "[非文字消息]" : content).append('\n');
            if (result.length() >= 60_000) break;
        }
        return result.toString();
    }

    private String messageContent(JsonNode message) {
        String direct = firstText(message, "text", "rawMessage", "message");
        if (direct != null) return direct;
        JsonNode content = message.get("content");
        if (content != null && content.isTextual() && !content.asText().isBlank()) return content.asText();
        JsonNode elements = message.path("elements");
        if (!elements.isArray()) return content == null || content.isNull() ? null : content.toString();
        List<String> parts = new ArrayList<>();
        elements.forEach(element -> {
            String text = firstText(element.path("textElement"), "content");
            if (text != null) parts.add(text);
            else if (element.hasNonNull("picElement")) parts.add("[图片]");
            else if (element.hasNonNull("pttElement")) parts.add("[语音]");
            else if (element.hasNonNull("videoElement")) parts.add("[视频]");
            else if (element.hasNonNull("fileElement")) parts.add("[文件]");
            else if (element.hasNonNull("marketFaceElement") || element.hasNonNull("faceElement")) parts.add("[表情]");
            else if (element.hasNonNull("multiForwardMsgElement")) parts.add("[合并转发]");
            else if (element.hasNonNull("arkElement")) parts.add("[卡片消息]");
        });
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    private AssistantDtos.QqConversationDraft emptyDraft(String warning) {
        return new AssistantDtos.QqConversationDraft("", List.of(), "未确认", "未确认", "未确认", "未确认",
                "未确认", "未确认", "未确认", "未确认", "", null, List.of(warning));
    }

    private String formatDetails(ImportRow row, AssistantDtos.QqConversationDraft draft) {
        return """
                【QQ来源】%s · %s%s
                【客户原话】%s
                【目标与动机】%s
                【已做行动】%s
                【主要顾虑】%s
                【决策参与人】%s
                【产品适配】%s
                【建议重点】%s
                【明确禁忌】%s
                【下一步】%s
                【待核实】%s
                """.formatted(row.chatType() == 2 ? "群聊" : row.chatType() == 1 ? "私聊" : "临时会话/服务号",
                value(row.sessionName()), row.chatType() == 2 ? " · 目标成员 " + value(row.targetSenderQq()) : "",
                String.join("；", safe(draft.customerQuotes())), value(draft.goalsMotivation()),
                value(draft.actionsTaken()), value(draft.mainConcerns()), value(draft.decisionParticipants()),
                value(draft.productFit()), value(draft.suggestedFocus()), value(draft.explicitTaboos()),
                value(draft.nextStep()), String.join("；", safe(draft.uncertainties())));
    }

    private void writeEncrypted(Path relative, byte[] raw) {
        try {
            Path target = retentionRoot().resolve(relative).normalize();
            if (!target.startsWith(retentionRoot())) throw new IllegalStateException("invalid retention path");
            Files.createDirectories(target.getParent());
            Files.write(target, cipher.encrypt(raw), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) {
            log.warn("QQ raw conversation storage failed exception={}", exception.getClass().getSimpleName());
            throw ApiException.unavailable("QQ_RAW_STORE_FAILED", "QQ 对话原文无法安全保存");
        }
    }
    private void deleteQuietly(Path relative) {
        try {
            Path target = retentionRoot().resolve(relative).normalize();
            if (target.startsWith(retentionRoot())) Files.deleteIfExists(target);
        } catch (Exception ignored) { }
    }
    private Path retentionRoot() { return Path.of(properties.retentionDir()).toAbsolutePath().normalize(); }
    private byte[] bytes(JsonNode node) {
        try { return objectMapper.writeValueAsBytes(node); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    static boolean isUniqueViolation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DataIntegrityViolationException) return true;
            if (current instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
            current = current.getCause();
        }
        return false;
    }
    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
    private String value(String value) { return value == null || value.isBlank() ? "未确认" : value.trim(); }
    private List<String> safe(List<String> value) { return value == null ? List.of() : value; }
    private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource result = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) result.addValue((String) values[index], values[index + 1]);
        return result;
    }

    private record ConnectionRow(UUID id, String instanceId, String status, String maskedQq, Instant createdAt) {}
    public record WorkbenchSession(UUID connectionId, String instanceId, UUID membershipId, UUID organizationId) {}
    private record ImportRow(UUID id, UUID membershipId, UUID customerId, int chatType, String peerUid, String peerQq,
                             String sessionName, String targetSenderUid, String targetSenderQq, boolean groupContextConfirmed,
                             Instant rangeStart, Instant rangeEnd, int messageCount, String path,
                             Instant expiresAt, String status, String draftJson, UUID interactionId) {}
}
