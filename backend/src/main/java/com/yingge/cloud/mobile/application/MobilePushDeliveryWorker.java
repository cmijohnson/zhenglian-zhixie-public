package com.yingge.cloud.mobile.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.mobile.infrastructure.MobilePushProvider;
import com.yingge.cloud.mobile.infrastructure.MobilePushTokenCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class MobilePushDeliveryWorker {
    private final MobilePushOutboxService outbox;
    private final MobilePushProvider provider;
    private final MobilePushTokenCipher cipher;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MobilePushDeliveryWorker(MobilePushOutboxService outbox, MobilePushProvider provider,
                                    MobilePushTokenCipher cipher, JdbcTemplate jdbc, ObjectMapper json) {
        this.outbox = outbox;
        this.provider = provider;
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.json = json;
    }

    @Scheduled(fixedDelayString = "${app.mobile.push-dispatch-delay-ms:30000}")
    public void dispatchPending() {
        for (MobilePushOutboxService.Dispatch dispatch : outbox.claim(20)) dispatch(dispatch);
    }

    void dispatch(MobilePushOutboxService.Dispatch dispatch) {
        Registration registration = latestRegistration(dispatch.recipientMembershipId());
        if (registration == null) {
            outbox.failed(dispatch.id(), null, "NO_ACTIVE_REGISTRATION", false);
            return;
        }
        if (provider.status() != MobilePushProvider.ProviderStatus.READY || !cipher.configured()) {
            outbox.failed(dispatch.id(), registration.id(), "NOT_CONFIGURED", false);
            return;
        }
        try {
            JsonNode payload = json.readTree(dispatch.payload());
            MobilePushProvider.DeliveryResult result = provider.send(cipher.decrypt(registration.ciphertext()),
                    new MobilePushProvider.PushMessage(payload.path("eventType").asText(),
                            payload.path("count").asInt(1), payload.path("deeplink").asText("/")));
            if (result.delivered()) {
                outbox.delivered(dispatch.id(), registration.id(), result.providerMessageId());
            } else {
                outbox.failed(dispatch.id(), registration.id(), safeCode(result.failureCode()), result.retryable());
            }
        } catch (RuntimeException exception) {
            outbox.failed(dispatch.id(), registration.id(), "PROVIDER_ERROR", true);
        } catch (Exception exception) {
            outbox.failed(dispatch.id(), registration.id(), "PAYLOAD_INVALID", false);
        }
    }

    private Registration latestRegistration(UUID membershipId) {
        List<Registration> values = jdbc.query("""
                select registration.id,registration.token_ciphertext
                from mobile_push_registrations registration
                join mobile_device_sessions session on session.id=registration.session_id
                where session.membership_id=? and session.status='ACTIVE' and registration.enabled=true
                order by registration.updated_at desc limit 1
                """, (rs, row) -> new Registration(rs.getObject("id", UUID.class),
                rs.getBytes("token_ciphertext")), membershipId);
        return values.isEmpty() ? null : values.getFirst();
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) return "PROVIDER_REJECTED";
        return value.trim().substring(0, Math.min(value.trim().length(), 120));
    }

    private record Registration(UUID id, byte[] ciphertext) {}
}
