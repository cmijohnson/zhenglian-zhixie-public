package com.yingge.cloud.mobile.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MobilePushOutboxService {
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MobilePushOutboxService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Enqueues only routing metadata. Customer names, message bodies and other CRM fields are deliberately absent.
     */
    @Transactional
    public void enqueue(UUID organizationId, UUID recipientMembershipId, String eventType,
                        String targetPath, String dedupeKey) {
        String safeEvent = required(eventType, 80);
        String safePath = safePath(targetPath);
        String safeDedupe = required(dedupeKey, 200);
        Instant now = Instant.now();
        jdbc.update("""
                insert into mobile_push_outbox
                (id,organization_id,recipient_membership_id,event_type,target_path,payload,dedupe_key,
                 status,attempt_count,max_attempts,available_at,expires_at,created_at,updated_at)
                select ?,?,?,?,?,cast(? as jsonb),?,'PENDING',0,3,?,?,?,?
                where exists(select 1 from memberships where id=? and organization_id=? and status='ACTIVE')
                on conflict (organization_id,recipient_membership_id,dedupe_key) do nothing
                """, UUID.randomUUID(), organizationId, recipientMembershipId, safeEvent, safePath,
                toJson(Map.of("eventType", safeEvent, "count", 1, "deeplink", safePath)), safeDedupe,
                Timestamp.from(now), Timestamp.from(now.plus(DEFAULT_TTL)), Timestamp.from(now), Timestamp.from(now),
                recipientMembershipId, organizationId);
    }

    @Transactional
    public List<Dispatch> claim(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        jdbc.update("""
                update mobile_push_outbox set status='EXPIRED',updated_at=now()
                where status in ('PENDING','PROCESSING') and expires_at<=now()
                """);
        List<UUID> ids = jdbc.queryForList("""
                select id from mobile_push_outbox
                where status='PENDING' and available_at<=now() and expires_at>now()
                  and attempt_count<max_attempts
                order by created_at for update skip locked limit ?
                """, UUID.class, safeLimit);
        if (ids.isEmpty()) return List.of();
        return ids.stream().map(this::claimOne).toList();
    }

    @Transactional
    public void delivered(UUID outboxId, UUID registrationId, String providerMessageId) {
        Dispatch row = locked(outboxId);
        delivery(row, registrationId, "DELIVERED", providerMessageId, null);
        jdbc.update("""
                update mobile_push_outbox set status='DELIVERED',delivered_at=now(),locked_at=null,
                    last_error_code=null,updated_at=now() where id=? and status='PROCESSING'
                """, outboxId);
    }

    @Transactional
    public void failed(UUID outboxId, UUID registrationId, String failureCode, boolean retryable) {
        Dispatch row = locked(outboxId);
        String cleanCode = required(failureCode, 120);
        boolean retry = retryable && row.attemptCount() < row.maxAttempts() && row.expiresAt().isAfter(Instant.now());
        delivery(row, registrationId, retry ? "RETRYABLE_FAILURE" : "PERMANENT_FAILURE", null, cleanCode);
        jdbc.update("""
                update mobile_push_outbox set status=?,available_at=?,locked_at=null,last_error_code=?,updated_at=now()
                where id=? and status='PROCESSING'
                """, retry ? "PENDING" : "FAILED",
                Timestamp.from(Instant.now().plusSeconds(Math.min(300, 15L * row.attemptCount()))),
                cleanCode, outboxId);
    }

    private Dispatch claimOne(UUID id) {
        jdbc.update("""
                update mobile_push_outbox set status='PROCESSING',attempt_count=attempt_count+1,
                    locked_at=now(),updated_at=now() where id=? and status='PENDING'
                """, id);
        return locked(id);
    }

    private Dispatch locked(UUID id) {
        List<Dispatch> rows = jdbc.query("""
                select id,organization_id,recipient_membership_id,event_type,target_path,payload::text,
                  attempt_count,max_attempts,expires_at
                from mobile_push_outbox where id=? for update
                """, (rs, row) -> new Dispatch(rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getObject("recipient_membership_id", UUID.class),
                rs.getString("event_type"), rs.getString("target_path"), rs.getString("payload"),
                rs.getInt("attempt_count"), rs.getInt("max_attempts"),
                rs.getTimestamp("expires_at").toInstant()), id);
        if (rows.isEmpty()) throw ApiException.notFound("MOBILE_PUSH_OUTBOX_NOT_FOUND", "推送队列记录不存在");
        return rows.getFirst();
    }

    private void delivery(Dispatch row, UUID registrationId, String status,
                          String providerMessageId, String failureCode) {
        jdbc.update("""
                insert into mobile_push_deliveries
                (id,outbox_id,registration_id,provider,attempt_number,status,provider_message_id,
                 failure_code,created_at,completed_at)
                values (?,?,?,'UNIPUSH',?,?,?,?,now(),now())
                on conflict (outbox_id,registration_id,attempt_number) do nothing
                """, UUID.randomUUID(), row.id(), registrationId, row.attemptCount(), status,
                cleanOptional(providerMessageId, 200), failureCode);
    }

    private String safePath(String path) {
        String value = required(path, 500);
        if (!value.startsWith("/") || value.startsWith("//") || value.contains("\\")) {
            throw ApiException.badRequest("MOBILE_PUSH_TARGET_INVALID", "推送目标路径无效");
        }
        return value;
    }

    private String required(String value, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw ApiException.badRequest("MOBILE_PUSH_METADATA_INVALID", "推送元数据无效");
        }
        return value.trim();
    }

    private String cleanOptional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        return value.trim().substring(0, Math.min(value.trim().length(), max));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("push payload serialization failed", exception);
        }
    }

    public record Dispatch(UUID id, UUID organizationId, UUID recipientMembershipId,
                           String eventType, String targetPath, String payload,
                           int attemptCount, int maxAttempts, Instant expiresAt) {}
}
