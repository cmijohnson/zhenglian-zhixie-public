package com.yingge.cloud.mobile.application;

import com.yingge.cloud.mobile.api.MobileDtos;
import com.yingge.cloud.mobile.infrastructure.MobilePushTokenCipher;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class MobilePushService {
    private final JdbcTemplate jdbc;
    private final MobilePushTokenCipher cipher;

    public MobilePushService(JdbcTemplate jdbc, MobilePushTokenCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @Transactional
    public void register(UUID sessionId, MobileDtos.PushRegistrationRequest body) {
        if (!"UNIPUSH".equals(body.provider())) {
            throw ApiException.badRequest("MOBILE_PUSH_PROVIDER_INVALID", "当前客户端仅支持 UNIPUSH");
        }
        byte[] encrypted = cipher.encrypt(body.registrationId());
        String hash = sha256(body.registrationId());
        jdbc.update("""
                delete from mobile_push_registrations where provider=? and token_hash=? and session_id<>?
                """, body.provider(), hash, sessionId);
        jdbc.update("""
                insert into mobile_push_registrations
                (id,session_id,provider,vendor,token_hash,token_ciphertext,enabled,created_at,updated_at,invalidated_at)
                values (?,?,?,?,?,?,true,now(),now(),null)
                on conflict (session_id,provider) do update set vendor=excluded.vendor,
                    token_hash=excluded.token_hash,token_ciphertext=excluded.token_ciphertext,
                    enabled=true,invalidated_at=null,updated_at=now()
                """, UUID.randomUUID(), sessionId, body.provider(), cleanVendor(body.vendor()), hash, encrypted);
    }

    @Transactional
    public void unregister(UUID sessionId) {
        jdbc.update("""
                update mobile_push_registrations set enabled=false,invalidated_at=now(),updated_at=now()
                where session_id=? and enabled=true
                """, sessionId);
    }

    @Transactional
    public MobileDtos.NotificationPreferences preferences(UUID membershipId,
                                                           MobileDtos.NotificationPreferences body) {
        jdbc.update("""
                insert into mobile_push_preferences
                (membership_id,urgent_reminders,tasks,consultations,announcements,releases)
                values (?,?,?,?,?,?)
                on conflict (membership_id) do update set
                    urgent_reminders=excluded.urgent_reminders,tasks=excluded.tasks,
                    consultations=excluded.consultations,announcements=excluded.announcements,
                    releases=excluded.releases,updated_at=now(),version=mobile_push_preferences.version+1
                """, membershipId, body.urgentReminders(), body.tasks(), body.consultations(),
                body.announcements(), body.releases());
        return body;
    }

    private String cleanVendor(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
