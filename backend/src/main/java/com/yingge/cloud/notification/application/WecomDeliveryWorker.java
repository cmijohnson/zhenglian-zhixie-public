package com.yingge.cloud.notification.application;

import com.yingge.cloud.notification.infrastructure.WebhookSecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class WecomDeliveryWorker {
    private final JdbcTemplate jdbc;
    private final WebhookSecretCipher cipher;
    private final RestClient restClient;
    private final TransactionTemplate transactions;

    public WecomDeliveryWorker(JdbcTemplate jdbc, WebhookSecretCipher cipher, RestClient.Builder restClients,
                               PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = restClients.clone().requestFactory(requestFactory).build();
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${yingge.webhook.delivery-delay-ms:30000}")
    public void deliverPending() {
        List<UUID> claimed = transactions.execute(status -> claimBatch());
        if (claimed == null) return;
        for (UUID id : claimed) deliver(id);
    }

    List<UUID> claimBatch() {
        jdbc.update("""
                update wecom_webhook_deliveries
                set status='RETRYING',next_attempt_at=now(),last_error_code='WORKER_LEASE_EXPIRED',updated_at=now()
                where status='SENDING' and updated_at<now()-interval '2 minutes'
                """);
        List<UUID> ids = jdbc.queryForList("""
                select id from wecom_webhook_deliveries
                where status in ('PENDING','RETRYING') and next_attempt_at<=now()
                order by created_at for update skip locked limit 20
                """, UUID.class);
        for (UUID id : ids) {
            jdbc.update("update wecom_webhook_deliveries set status='SENDING',updated_at=now() where id=?", id);
        }
        return ids;
    }

    void deliver(UUID id) {
        Delivery row = jdbc.query("""
                select delivery.id,delivery.attempt_count,delivery.title,delivery.redacted_summary,
                       delivery.target_path,channel.encrypted_url
                from wecom_webhook_deliveries delivery
                join wecom_webhook_channels channel on channel.id=delivery.channel_id and channel.active=true
                where delivery.id=? and delivery.status='SENDING'
                """, (rs, index) -> new Delivery(rs.getObject("id", UUID.class), rs.getInt("attempt_count"),
                rs.getString("title"), rs.getString("redacted_summary"), rs.getString("target_path"),
                rs.getBytes("encrypted_url")), id).stream().findFirst().orElse(null);
        if (row == null) {
            jdbc.update("""
                    update wecom_webhook_deliveries set status='FAILED',last_error_code='CHANNEL_UNAVAILABLE',
                           updated_at=now() where id=? and status='SENDING'
                    """, id);
            return;
        }
        int attempt = row.attemptCount() + 1;
        try {
            String content = row.title() + "\n" + row.summary()
                    + (row.targetPath() == null ? "" : "\n系统路径：" + row.targetPath());
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri(URI.create(cipher.decrypt(row.encryptedUrl())))
                    .body(Map.of("msgtype", "text", "text", Map.of("content", content)))
                    .retrieve().body(Map.class);
            Number errorCode = response == null ? null : (Number) response.get("errcode");
            if (errorCode == null || errorCode.intValue() != 0) {
                throw new IllegalStateException("WECOM_BUSINESS_ERROR");
            }
            jdbc.update("""
                    update wecom_webhook_deliveries set status='DELIVERED',attempt_count=?,delivered_at=now(),
                           last_error_code=null,updated_at=now() where id=?
                    """, attempt, id);
        } catch (RuntimeException exception) {
            boolean failed = attempt >= 3;
            Instant next = Instant.now().plus(switch (attempt) {
                case 1 -> 1;
                case 2 -> 5;
                default -> 15;
            }, ChronoUnit.MINUTES);
            jdbc.update("""
                    update wecom_webhook_deliveries set status=?,attempt_count=?,next_attempt_at=?,
                           last_error_code=?,updated_at=now() where id=?
                    """, failed ? "FAILED" : "RETRYING", attempt, Timestamp.from(next),
                    safeError(exception), id);
        }
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Shanghai")
    public void purgeExpiredNotifications() {
        jdbc.update("delete from system_notifications where expires_at<now()");
    }

    private String safeError(RuntimeException exception) {
        if (exception instanceof org.springframework.web.client.ResourceAccessException) return "NETWORK_ERROR";
        if (exception instanceof org.springframework.web.client.HttpStatusCodeException http) {
            return "HTTP_" + http.getStatusCode().value();
        }
        return "DELIVERY_REJECTED";
    }

    record Delivery(UUID id, int attemptCount, String title, String summary, String targetPath, byte[] encryptedUrl) {}
}
