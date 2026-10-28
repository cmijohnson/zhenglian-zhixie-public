package com.yingge.cloud.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.notification.api.NotificationDtos;
import com.yingge.cloud.notification.infrastructure.WebhookSecretCipher;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.mobile.application.MobilePushOutboxService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class NotificationService {
    public static final Set<String> EVENT_TYPES = Set.of(
            "CUSTOMER_CREATED", "CUSTOMER_QUALITY_CHANGED", "CUSTOMER_POOL_RELEASED",
            "CUSTOMER_TEMP_CLAIMED", "CUSTOMER_RETURNED_TO_POOL", "SECOND_LINE_REQUIRED",
            "SECOND_LINE_CONTACT_AUTH", "SECOND_LINE_TAKEOVER", "BUSINESS_WON_PENDING",
            "BUSINESS_WON", "BUSINESS_LOST_TO_COMPETITOR", "MEMBER_DEPARTURE", "GOVERNANCE_TASK",
            "A_CLASS_STALE"
            , "BUSINESS_QUOTE_LOCK_PENDING", "BUSINESS_QUOTE_LOCK_REJECTED", "BUSINESS_FORMALLY_QUOTED", "BUSINESS_PARTICIPANT_CHANGE",
            "DEAL_FORECAST_PENDING", "DEAL_FORECAST_CONFIRMED", "DEAL_FORECAST_RETURNED", "DEAL_FORECAST_OVERDUE",
            "CUSTOMER_DEAL_REQUEST_PENDING", "CUSTOMER_DEAL_REQUEST_APPROVED", "CUSTOMER_DEAL_REQUEST_REJECTED",
            "CUSTOMER_OWNERSHIP_TRANSFERRED"
    );

    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;
    private final WebhookSecretCipher cipher;
    private final ObjectMapper json;
    private final MobilePushOutboxService mobilePush;

    public NotificationService(JdbcTemplate jdbc, CurrentActorProvider actors, PermissionService permissions,
                               AuditService audit, WebhookSecretCipher cipher, ObjectMapper json,
                               MobilePushOutboxService mobilePush) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
        this.cipher = cipher;
        this.json = json;
        this.mobilePush = mobilePush;
    }

    @Transactional(readOnly = true)
    public NotificationDtos.InboxResponse inbox(boolean unreadOnly, int size) {
        CurrentActor actor = actors.require();
        int limited = Math.min(Math.max(size, 1), 100);
        String unread = unreadOnly ? " and read_at is null" : "";
        List<NotificationDtos.NotificationResponse> rows = jdbc.query("""
                select id,event_type,title,summary,target_path,read_at,created_at,expires_at
                from system_notifications
                where organization_id=? and recipient_membership_id=? and expires_at>now()
                """ + unread + " order by created_at desc limit ?", (rs, row) ->
                new NotificationDtos.NotificationResponse(rs.getObject("id", UUID.class), rs.getString("event_type"),
                        rs.getString("title"), rs.getString("summary"), rs.getString("target_path"),
                        rs.getTimestamp("read_at") != null, rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()), actor.organizationId(), actor.membershipId(), limited);
        Long unreadCount = jdbc.queryForObject("""
                select count(*) from system_notifications
                where organization_id=? and recipient_membership_id=? and read_at is null and expires_at>now()
                """, Long.class, actor.organizationId(), actor.membershipId());
        return new NotificationDtos.InboxResponse(unreadCount == null ? 0 : unreadCount, rows);
    }

    @Transactional
    public void markRead(UUID id) {
        CurrentActor actor = actors.require();
        int changed = jdbc.update("""
                update system_notifications set read_at=coalesce(read_at,now())
                where id=? and organization_id=? and recipient_membership_id=?
                """, id, actor.organizationId(), actor.membershipId());
        if (changed == 0) throw ApiException.notFound("NOTIFICATION_NOT_FOUND", "通知不存在");
    }

    @Transactional
    public int markAllRead() {
        CurrentActor actor = actors.require();
        return jdbc.update("""
                update system_notifications set read_at=now()
                where organization_id=? and recipient_membership_id=? and read_at is null and expires_at>now()
                """, actor.organizationId(), actor.membershipId());
    }

    @Transactional
    public void publish(UUID organizationId, Collection<UUID> recipients, String eventType, String title,
                        String redactedSummary, String targetPath, String dedupeKey) {
        validateEvent(eventType);
        String cleanTitle = required(title, 200, "NOTIFICATION_TITLE_REQUIRED", "通知标题不能为空");
        String cleanSummary = required(redactedSummary, 1000, "NOTIFICATION_SUMMARY_REQUIRED", "通知摘要不能为空");
        String cleanPath = safePath(targetPath);
        String cleanDedupe = required(dedupeKey, 200, "NOTIFICATION_DEDUPE_REQUIRED", "通知幂等键不能为空");
        for (UUID recipient : new LinkedHashSet<>(recipients)) {
            jdbc.update("""
                    insert into system_notifications
                    (id,organization_id,recipient_membership_id,event_type,title,summary,target_path,dedupe_key)
                    select ?,?,?,?,?,?,?,? where exists(
                        select 1 from memberships where id=? and organization_id=? and status='ACTIVE')
                    on conflict (organization_id,recipient_membership_id,dedupe_key) do nothing
                    """, UUID.randomUUID(), organizationId, recipient, eventType, cleanTitle, cleanSummary,
                    cleanPath, cleanDedupe, recipient, organizationId);
            mobilePush.enqueue(organizationId, recipient, eventType, cleanPath, cleanDedupe);
        }
        String subscription = toJson(List.of(eventType));
        List<UUID> channelIds = jdbc.queryForList("""
                select id from wecom_webhook_channels
                where organization_id=? and active=true and event_types @> cast(? as jsonb)
                """, UUID.class, organizationId, subscription);
        for (UUID channelId : channelIds) {
            jdbc.update("""
                    insert into wecom_webhook_deliveries
                    (id,organization_id,channel_id,event_type,title,redacted_summary,target_path,dedupe_key)
                    values (?,?,?,?,?,?,?,?) on conflict (channel_id,dedupe_key) do nothing
                    """, UUID.randomUUID(), organizationId, channelId, eventType, cleanTitle,
                    cleanSummary, cleanPath, cleanDedupe);
        }
    }

    @Transactional(readOnly = true)
    public List<NotificationDtos.WebhookResponse> webhooks() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return jdbc.query("""
                select id,name,event_types::text,active,version,created_at,updated_at
                from wecom_webhook_channels where organization_id=? order by created_at
                """, (rs, row) -> new NotificationDtos.WebhookResponse(
                rs.getObject("id", UUID.class), rs.getString("name"), readEvents(rs.getString("event_types")),
                rs.getBoolean("active"), true, rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), actor.organizationId());
    }

    @Transactional
    public NotificationDtos.WebhookResponse createWebhook(NotificationDtos.WebhookRequest body) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        String name = required(body.name(), 120, "WEBHOOK_NAME_REQUIRED", "Webhook 名称不能为空");
        String url = validateWebhookUrl(body.webhookUrl());
        Set<String> events = validateEvents(body.eventTypes());
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into wecom_webhook_channels
                    (id,organization_id,name,encrypted_url,event_types,active,created_by_membership_id)
                    values (?,?,?,?,cast(? as jsonb),?,?)
                    """, id, actor.organizationId(), name, cipher.encrypt(url), toJson(events),
                    body.active() == null || body.active(), actor.membershipId());
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("WEBHOOK_NAME_EXISTS", "同名 Webhook 已存在");
        }
        audit.record(actor, "WECOM_WEBHOOK_CREATED", "WECOM_WEBHOOK", id.toString(), "SUCCESS",
                Map.of("name", name, "eventTypes", events));
        return webhooks().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public NotificationDtos.WebhookResponse updateWebhook(UUID id, NotificationDtos.WebhookRequest body) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        String name = required(body.name(), 120, "WEBHOOK_NAME_REQUIRED", "Webhook 名称不能为空");
        Set<String> events = validateEvents(body.eventTypes());
        byte[] encrypted = body.webhookUrl() == null || body.webhookUrl().isBlank()
                ? null : cipher.encrypt(validateWebhookUrl(body.webhookUrl()));
        long expected = body.expectedVersion() == null ? -1 : body.expectedVersion();
        int changed = encrypted == null
                ? jdbc.update("""
                    update wecom_webhook_channels set name=?,event_types=cast(? as jsonb),active=?,updated_at=now(),version=version+1
                    where id=? and organization_id=? and (?<0 or version=?)
                    """, name, toJson(events), body.active() == null || body.active(), id,
                    actor.organizationId(), expected, expected)
                : jdbc.update("""
                    update wecom_webhook_channels set name=?,encrypted_url=?,event_types=cast(? as jsonb),active=?,updated_at=now(),version=version+1
                    where id=? and organization_id=? and (?<0 or version=?)
                    """, name, encrypted, toJson(events), body.active() == null || body.active(), id,
                    actor.organizationId(), expected, expected);
        if (changed == 0) throw ApiException.conflict("WEBHOOK_VERSION_CONFLICT", "Webhook 已被修改，请刷新后重试");
        audit.record(actor, "WECOM_WEBHOOK_UPDATED", "WECOM_WEBHOOK", id.toString(), "SUCCESS",
                Map.of("name", name, "eventTypes", events, "secretRotated", encrypted != null));
        return webhooks().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("WEBHOOK_NOT_FOUND", "Webhook 不存在"));
    }

    @Transactional
    public void disableWebhook(UUID id) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        int changed = jdbc.update("""
                update wecom_webhook_channels set active=false,updated_at=now(),version=version+1
                where id=? and organization_id=? and active=true
                """, id, actor.organizationId());
        if (changed == 0) throw ApiException.notFound("WEBHOOK_NOT_FOUND", "Webhook 不存在或已停用");
        audit.record(actor, "WECOM_WEBHOOK_DISABLED", "WECOM_WEBHOOK", id.toString(), "SUCCESS", null);
    }

    @Transactional(readOnly = true)
    public List<NotificationDtos.DeliveryResponse> deliveries(int size) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return jdbc.query("""
                select delivery.id,delivery.channel_id,channel.name channel_name,delivery.event_type,
                       delivery.title,delivery.status,delivery.attempt_count,delivery.last_error_code,
                       delivery.next_attempt_at,delivery.delivered_at,delivery.created_at
                from wecom_webhook_deliveries delivery join wecom_webhook_channels channel on channel.id=delivery.channel_id
                where delivery.organization_id=? order by delivery.created_at desc limit ?
                """, (rs, row) -> new NotificationDtos.DeliveryResponse(
                rs.getObject("id", UUID.class), rs.getObject("channel_id", UUID.class), rs.getString("channel_name"),
                rs.getString("event_type"), rs.getString("title"), rs.getString("status"), rs.getInt("attempt_count"),
                rs.getString("last_error_code"), rs.getTimestamp("next_attempt_at").toInstant(),
                rs.getTimestamp("delivered_at") == null ? null : rs.getTimestamp("delivered_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()), actor.organizationId(), Math.min(Math.max(size, 1), 200));
    }

    @Transactional
    public void retryDelivery(UUID id) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        int changed = jdbc.update("""
                update wecom_webhook_deliveries set status='PENDING',attempt_count=0,next_attempt_at=now(),
                       last_error_code=null,updated_at=now()
                where id=? and organization_id=? and status='FAILED'
                """, id, actor.organizationId());
        if (changed == 0) throw ApiException.conflict("WEBHOOK_DELIVERY_NOT_RETRYABLE", "该投递不存在或当前不可重试");
        audit.record(actor, "WECOM_WEBHOOK_DELIVERY_RETRIED", "WECOM_DELIVERY", id.toString(), "SUCCESS", null);
    }

    private Set<String> validateEvents(Collection<String> values) {
        if (values == null || values.isEmpty()) throw ApiException.badRequest("WEBHOOK_EVENTS_REQUIRED", "至少选择一个通知事件");
        Set<String> events = new LinkedHashSet<>(values);
        if (!EVENT_TYPES.containsAll(events)) throw ApiException.badRequest("WEBHOOK_EVENT_INVALID", "包含不支持的通知事件");
        return Set.copyOf(events);
    }

    private void validateEvent(String eventType) {
        if (!EVENT_TYPES.contains(eventType)) throw ApiException.badRequest("NOTIFICATION_EVENT_INVALID", "通知事件类型无效");
    }

    private String validateWebhookUrl(String value) {
        String url = required(value, 1000, "WEBHOOK_URL_REQUIRED", "企业微信 Webhook 地址不能为空");
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"qyapi.weixin.qq.com".equalsIgnoreCase(uri.getHost())
                    || !"/cgi-bin/webhook/send".equals(uri.getPath()) || uri.getQuery() == null
                    || !uri.getQuery().matches("(?:^|.*&)key=[A-Za-z0-9_-]{16,}(?:&.*|$)")
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid url");
            }
            return uri.toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("WEBHOOK_URL_INVALID", "请输入有效的企业微信群机器人 Webhook 地址");
        }
    }

    private String safePath(String value) {
        if (value == null || value.isBlank()) return null;
        String path = value.trim();
        if (!path.startsWith("/") || path.startsWith("//") || path.length() > 500) {
            throw ApiException.badRequest("NOTIFICATION_TARGET_INVALID", "通知目标必须是系统内相对路径");
        }
        return path;
    }

    private String required(String value, int max, String code, String message) {
        String clean = value == null ? "" : value.trim();
        if (clean.isEmpty()) throw ApiException.badRequest(code, message);
        if (clean.length() > max) throw ApiException.badRequest(code + "_TOO_LONG", message.replace("不能为空", "过长"));
        return clean;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to encode notification data", exception);
        }
    }

    private Set<String> readEvents(String value) {
        try {
            return Set.copyOf(json.readValue(value, json.getTypeFactory().constructCollectionType(Set.class, String.class)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to decode webhook subscriptions", exception);
        }
    }
}
