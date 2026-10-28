package com.yingge.cloud.feishu.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.deal.application.DealConfirmationService;
import com.yingge.cloud.feishu.api.FeishuDealBotDtos;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FeishuDealBotAdminService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;
    private final FeishuIntegrationService integrations;
    private final FeishuBitableClient client;
    private final DealConfirmationService deals;

    public FeishuDealBotAdminService(JdbcTemplate jdbc, CurrentActorProvider actors,
                                     PermissionService permissions, AuditService audit,
                                     FeishuIntegrationService integrations, FeishuBitableClient client,
                                     DealConfirmationService deals) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
        this.integrations = integrations;
        this.client = client;
        this.deals = deals;
    }

    @Transactional(readOnly = true)
    public FeishuDealBotDtos.SettingsResponse configuration() {
        CurrentActor actor = requireAdmin();
        return find(actor.organizationId());
    }

    @Transactional
    public FeishuDealBotDtos.SettingsResponse save(FeishuDealBotDtos.SettingsRequest body) {
        CurrentActor actor = requireAdmin();
        UUID integrationId = integrationId(actor.organizationId());
        FeishuDealBotDtos.SettingsResponse current = find(actor.organizationId());
        String submission = id(body.submissionChatId(), "FEISHU_SUBMISSION_CHAT_INVALID", "请选择成单提交群");
        String broadcast = id(body.broadcastChatId(), "FEISHU_BROADCAST_CHAT_INVALID", "请选择全员广播群");
        if (submission.equals(broadcast)) {
            throw ApiException.badRequest("FEISHU_DEAL_CHATS_MUST_DIFFER", "成单提交群和全员广播群不能相同");
        }
        boolean enabled = Boolean.TRUE.equals(body.enabled());
        boolean privacyAccepted = Boolean.TRUE.equals(body.privacyAcknowledged())
                || current != null && current.privacyAcknowledgedAt() != null;
        boolean financialVisibilityAccepted = Boolean.TRUE.equals(body.financialVisibilityAcknowledged())
                || current != null && current.financialVisibilityAcknowledgedAt() != null;
        boolean quickCreationEnabled = Boolean.TRUE.equals(body.quickCustomerCreationEnabled());
        if (enabled && !privacyAccepted) {
            throw ApiException.badRequest("FEISHU_DEAL_BROADCAST_PRIVACY_REQUIRED", "启用前必须确认广播将公开客户完整姓名");
        }
        if (enabled && !financialVisibilityAccepted) {
            throw ApiException.badRequest("FEISHU_DEAL_FINANCIAL_VISIBILITY_REQUIRED",
                    "启用前必须确认订单金额和奖金将在组织内展示");
        }
        FeishuIntegrationService.IntegrationRow integration = integrations.findById(integrationId);
        FeishuBitableClient.BotInfo bot = client.botInfo(integrations.credentialsFor(integration));
        Map<String, FeishuBitableClient.ChatInfo> chats = client.listChats(integrations.credentialsFor(integration))
                .stream().collect(java.util.stream.Collectors.toMap(FeishuBitableClient.ChatInfo::chatId, value -> value));
        if (!chats.containsKey(submission) || !chats.containsKey(broadcast)) {
            throw ApiException.badRequest("FEISHU_DEAL_CHAT_NOT_ACCESSIBLE", "机器人必须已加入所选的两个群聊");
        }
        long defaultBonus = body.defaultBonusCents() == null ? 0 : body.defaultBonusCents();
        String broadcastTitle = text(body.broadcastTitle(), "🎉 英格科技成单播报 🎉");
        String leaderboardTitle = text(body.leaderboardTitle(), "🌹英格科技我为你操碎了心🌹");
        String footer = text(body.leaderboardFooter(), "👀看看又是哪个小笨蛋饿死了👀\n🔨每日任务：激活 登记 对接🔨");
        if (current == null) {
            jdbc.update("""
                    insert into feishu_deal_bot_settings
                    (id,integration_id,organization_id,enabled,submission_chat_id,submission_chat_name,
                     broadcast_chat_id,broadcast_chat_name,bot_open_id,bot_name,default_bonus_cents,
                     broadcast_title,leaderboard_title,leaderboard_footer,privacy_acknowledged_at,
                     quick_customer_creation_enabled,financial_visibility_acknowledged_at,
                     updated_by_membership_id)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,case when ? then now() end,?,
                            case when ? then now() end,?)
                    """, UUID.randomUUID(), integrationId, actor.organizationId(), enabled, submission,
                    chats.get(submission).name(), broadcast, chats.get(broadcast).name(), bot.openId(), bot.name(),
                    defaultBonus, broadcastTitle, leaderboardTitle, footer, privacyAccepted, quickCreationEnabled,
                    financialVisibilityAccepted, actor.membershipId());
        } else {
            int changed = jdbc.update("""
                    update feishu_deal_bot_settings set enabled=?,submission_chat_id=?,submission_chat_name=?,
                        broadcast_chat_id=?,broadcast_chat_name=?,bot_open_id=?,bot_name=?,default_bonus_cents=?,
                        broadcast_title=?,leaderboard_title=?,leaderboard_footer=?,
                        privacy_acknowledged_at=case when ? then coalesce(privacy_acknowledged_at,now()) end,
                        quick_customer_creation_enabled=?,
                        financial_visibility_acknowledged_at=case when ? then
                            coalesce(financial_visibility_acknowledged_at,now()) end,
                        updated_by_membership_id=?,updated_at=now(),version=version+1
                    where organization_id=? and version=?
                    """, enabled, submission, chats.get(submission).name(), broadcast, chats.get(broadcast).name(),
                    bot.openId(), bot.name(), defaultBonus, broadcastTitle, leaderboardTitle, footer,
                    privacyAccepted, quickCreationEnabled, financialVisibilityAccepted, actor.membershipId(),
                    actor.organizationId(), body.expectedVersion());
            if (changed != 1) throw ApiException.conflict("FEISHU_DEAL_BOT_VERSION_CONFLICT", "机器人设置已更新，请刷新后重试");
        }
        audit.record(actor, "FEISHU_DEAL_BOT_CONFIGURED", "FEISHU_DEAL_BOT", actor.organizationId().toString(),
                "SUCCESS", Map.of("enabled", enabled, "submissionChatId", submission,
                        "broadcastChatId", broadcast, "privacyAcknowledged", privacyAccepted,
                        "quickCustomerCreationEnabled", quickCreationEnabled,
                        "financialVisibilityAcknowledged", financialVisibilityAccepted));
        return find(actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<FeishuDealBotDtos.ChatResponse> chats() {
        CurrentActor actor = requireAdmin();
        FeishuIntegrationService.IntegrationRow integration = integrations.findById(integrationId(actor.organizationId()));
        return client.listChats(integrations.credentialsFor(integration)).stream()
                .map(chat -> new FeishuDealBotDtos.ChatResponse(chat.chatId(), chat.name(), chat.description())).toList();
    }

    @Transactional
    public FeishuDealBotDtos.TestResponse test() {
        CurrentActor actor = requireAdmin();
        FeishuDealBotDtos.SettingsResponse setting = requireConfigured(actor.organizationId());
        FeishuIntegrationService.IntegrationRow integration = integrations.findById(integrationId(actor.organizationId()));
        FeishuBitableClient.Credentials credentials = integrations.credentialsFor(integration);
        FeishuBitableClient.BotInfo bot = client.botInfo(credentials);
        String marker = "英格云成单机器人连接测试（不产生业务数据）";
        client.sendChatMessage(credentials, setting.submissionChatId(), "text", jsonText(marker), UUID.randomUUID());
        client.sendChatMessage(credentials, setting.broadcastChatId(), "text", jsonText(marker), UUID.randomUUID());
        audit.record(actor, "FEISHU_DEAL_BOT_TESTED", "FEISHU_DEAL_BOT", setting.id().toString(), "SUCCESS", null);
        return new FeishuDealBotDtos.TestResponse(true, bot.name(), setting.submissionChatName(),
                setting.broadcastChatName(), Instant.now());
    }

    @Transactional
    public FeishuDealBotDtos.LeaderboardResponse publishLeaderboard() {
        CurrentActor actor = requireAdmin();
        requireConfigured(actor.organizationId());
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        deals.enqueueLeaderboard(actor.organizationId(), today);
        audit.record(actor, "FEISHU_DEAL_LEADERBOARD_QUEUED", "FEISHU_DEAL_LEADERBOARD", today.toString(),
                "SUCCESS", null);
        return deals.leaderboardFor(actor.organizationId(), today);
    }

    @Transactional(readOnly = true)
    public List<FeishuDealBotDtos.MessageFailureResponse> failures(int size) {
        CurrentActor actor = requireAdmin();
        return jdbc.query("""
                select id,purpose,chat_id,attempt_count,last_error_code,created_at,updated_at
                from feishu_deal_message_outbox where organization_id=? and status='FAILED'
                order by updated_at desc limit ?
                """, (rs, row) -> new FeishuDealBotDtos.MessageFailureResponse(rs.getObject("id", UUID.class),
                        rs.getString("purpose"), rs.getString("chat_id"), rs.getInt("attempt_count"),
                        rs.getString("last_error_code"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()), actor.organizationId(),
                Math.min(200, Math.max(1, size)));
    }

    @Transactional
    public void retry(UUID id) {
        CurrentActor actor = requireAdmin();
        int changed = jdbc.update("""
                update feishu_deal_message_outbox set status='PENDING',attempt_count=0,next_attempt_at=now(),
                    last_error_code=null,updated_at=now() where id=? and organization_id=? and status='FAILED'
                """, id, actor.organizationId());
        if (changed != 1) throw ApiException.conflict("FEISHU_MESSAGE_NOT_RETRYABLE", "消息不存在或当前不可重试");
        audit.record(actor, "FEISHU_DEAL_MESSAGE_RETRIED", "FEISHU_MESSAGE_OUTBOX", id.toString(), "SUCCESS", null);
    }

    private FeishuDealBotDtos.SettingsResponse requireConfigured(UUID organizationId) {
        FeishuDealBotDtos.SettingsResponse setting = find(organizationId);
        if (setting == null || !setting.enabled()) {
            throw ApiException.conflict("FEISHU_DEAL_BOT_NOT_ENABLED", "请先配置并启用飞书成单机器人");
        }
        return setting;
    }

    private FeishuDealBotDtos.SettingsResponse find(UUID organizationId) {
        return jdbc.query("select * from feishu_deal_bot_settings where organization_id=?", this::map,
                organizationId).stream().findFirst().orElse(null);
    }

    private FeishuDealBotDtos.SettingsResponse map(ResultSet rs, int row) throws SQLException {
        Timestamp accepted = rs.getTimestamp("privacy_acknowledged_at");
        Timestamp financialAccepted = rs.getTimestamp("financial_visibility_acknowledged_at");
        return new FeishuDealBotDtos.SettingsResponse(rs.getObject("id", UUID.class), rs.getBoolean("enabled"),
                rs.getString("submission_chat_id"), rs.getString("submission_chat_name"),
                rs.getString("broadcast_chat_id"), rs.getString("broadcast_chat_name"),
                rs.getString("bot_name"), rs.getLong("default_bonus_cents"), rs.getString("broadcast_title"),
                rs.getString("leaderboard_title"), rs.getString("leaderboard_footer"),
                accepted == null ? null : accepted.toInstant(), rs.getBoolean("quick_customer_creation_enabled"),
                financialAccepted == null ? null : financialAccepted.toInstant(), rs.getLong("version"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private UUID integrationId(UUID organizationId) {
        return jdbc.query("""
                select id from feishu_bitable_integrations where organization_id=?
                """, (rs, row) -> rs.getObject("id", UUID.class), organizationId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("FEISHU_INTEGRATION_NOT_CONFIGURED", "尚未配置飞书企业应用"));
    }

    private String id(String value, String code, String message) {
        String clean = value == null ? "" : value.trim();
        if (!clean.matches("[A-Za-z0-9_-]{3,128}")) throw ApiException.badRequest(code, message);
        return clean;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String jsonText(String value) {
        return "{\"text\":\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return actor;
    }
}
