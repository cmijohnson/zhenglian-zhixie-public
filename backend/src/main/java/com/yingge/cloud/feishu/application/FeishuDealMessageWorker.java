package com.yingge.cloud.feishu.application;

import com.yingge.cloud.deal.application.DealConfirmationService;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.feishu.worker-enabled", havingValue = "true", matchIfMissing = true)
public class FeishuDealMessageWorker {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final FeishuIntegrationService integrations;
    private final FeishuBitableClient client;
    private final DealConfirmationService deals;

    public FeishuDealMessageWorker(JdbcTemplate jdbc, FeishuIntegrationService integrations,
                                   FeishuBitableClient client, DealConfirmationService deals) {
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.client = client;
        this.deals = deals;
    }

    @Scheduled(initialDelay = 10000, fixedDelay = 10000)
    @Transactional
    public void deliver() {
        List<PendingMessage> messages = jdbc.query("""
                select id,integration_id,message_type,payload::text,message_uuid,attempt_count
                from feishu_deal_message_outbox
                where status in ('PENDING','RETRYING') and next_attempt_at<=now()
                order by created_at for update skip locked limit 20
                """, (rs, row) -> new PendingMessage(rs.getObject("id", UUID.class),
                rs.getObject("integration_id", UUID.class), rs.getString("message_type"),
                rs.getString("payload"), rs.getObject("message_uuid", UUID.class), rs.getInt("attempt_count")));
        for (PendingMessage message : messages) deliver(message);
    }

    private void deliver(PendingMessage message) {
        int attempt = message.attemptCount() + 1;
        jdbc.update("""
                update feishu_deal_message_outbox set status='SENDING',attempt_count=?,updated_at=now() where id=?
                """, attempt, message.id());
        try {
            FeishuIntegrationService.IntegrationRow integration = integrations.findById(message.integrationId());
            if (integration == null) throw new FeishuBitableClient.FeishuClientException("FEISHU_INTEGRATION_NOT_CONFIGURED");
            String chatId = jdbc.queryForObject("select chat_id from feishu_deal_message_outbox where id=?",
                    String.class, message.id());
            String remoteId = client.sendChatMessage(integrations.credentialsFor(integration), chatId,
                    message.messageType(), message.payload(), message.messageUuid());
            jdbc.update("""
                    update feishu_deal_message_outbox set status='SENT',remote_message_id=?,sent_at=now(),
                        last_error_code=null,updated_at=now() where id=?
                    """, remoteId, message.id());
            jdbc.update("""
                    update feishu_deal_leaderboard_runs set status='SENT',completed_at=now()
                    where message_outbox_id=? and status='PENDING'
                    """, message.id());
        } catch (RuntimeException exception) {
            String error = FeishuBitableClient.safeFailureCode(exception);
            String status = attempt >= 5 ? "FAILED" : "RETRYING";
            jdbc.update("""
                    update feishu_deal_message_outbox set status=?,last_error_code=?,
                        next_attempt_at=now()+(? * interval '1 minute'),updated_at=now() where id=?
                    """, status, error, Math.min(30, 1 << Math.min(attempt, 5)), message.id());
            if ("FAILED".equals(status)) jdbc.update("""
                    update feishu_deal_leaderboard_runs set status='FAILED',completed_at=now()
                    where message_outbox_id=? and status='PENDING'
                    """, message.id());
        }
    }

    @Scheduled(cron = "0 0 22 * * *", zone = "Asia/Shanghai")
    @Transactional
    public void publishDailyLeaderboards() {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<BotTarget> targets = jdbc.query("""
                select integration_id,organization_id from feishu_deal_bot_settings
                where enabled=true and broadcast_chat_id is not null order by organization_id
                """, (rs, row) -> new BotTarget(rs.getObject("integration_id", UUID.class),
                rs.getObject("organization_id", UUID.class)));
        for (BotTarget target : targets) {
            UUID runId = UUID.randomUUID();
            int inserted = jdbc.update("""
                    insert into feishu_deal_leaderboard_runs
                    (id,integration_id,organization_id,leaderboard_date,status)
                    values (?,?,?,?,'PENDING') on conflict (integration_id,leaderboard_date) do nothing
                    """, runId, target.integrationId(), target.organizationId(), today);
            if (inserted == 0) continue;
            UUID outboxId = deals.enqueueLeaderboard(target.organizationId(), today);
            jdbc.update("update feishu_deal_leaderboard_runs set message_outbox_id=? where id=?", outboxId, runId);
        }
    }

    private record PendingMessage(UUID id, UUID integrationId, String messageType, String payload,
                                  UUID messageUuid, int attemptCount) {}
    private record BotTarget(UUID integrationId, UUID organizationId) {}
}
