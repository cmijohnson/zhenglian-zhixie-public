package com.yingge.cloud.mobile.application;

import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.auth.infrastructure.CloudUserDetailsService;
import com.yingge.cloud.mobile.api.MobileDtos;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MobileWebSessionService {
    private static final Map<String, String> TARGETS = Map.of(
            "QQ_WORKBENCH", "/qq-chat",
            "OAUTH_APPROVAL", "/oauth/authorize",
            "AGENT_APPROVAL", "/agent"
    );

    private final JdbcTemplate jdbc;
    private final CloudUserDetailsService users;
    private final SecureRandom random = new SecureRandom();

    public MobileWebSessionService(JdbcTemplate jdbc, CloudUserDetailsService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional
    public MobileDtos.WebSessionTicketResponse create(UUID sessionId, UUID membershipId, UUID organizationId,
                                                       String target) {
        String path = TARGETS.get(target);
        if (path == null) throw ApiException.badRequest("MOBILE_WEB_TARGET_INVALID", "不支持的网页功能");
        Integer active = jdbc.queryForObject("""
                select count(*) from mobile_device_sessions
                where id=? and membership_id=? and organization_id=? and status='ACTIVE'
                """, Integer.class, sessionId, membershipId, organizationId);
        if (active == null || active != 1) {
            throw ApiException.conflict("MOBILE_SESSION_REVOKED", "移动设备会话已失效");
        }
        String ticket = randomToken();
        Instant expiresAt = Instant.now().plusSeconds(60);
        jdbc.update("""
                insert into mobile_webview_tickets
                (id,session_id,membership_id,organization_id,ticket_hash,target,target_path,created_at,expires_at)
                values (?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), sessionId, membershipId, organizationId, sha256(ticket), target, path,
                Timestamp.from(Instant.now()), Timestamp.from(expiresAt));
        return new MobileDtos.WebSessionTicketResponse(ticket,
                "/api/v1/mobile/web-sessions/exchange", expiresAt);
    }

    @Transactional
    public Exchange exchange(String ticket) {
        if (ticket == null || ticket.length() < 32 || ticket.length() > 512) {
            throw ApiException.badRequest("MOBILE_WEB_TICKET_INVALID", "网页会话凭据无效");
        }
        Instant now = Instant.now();
        List<TicketRow> rows = jdbc.query("""
                select ticket.id,ticket.session_id,ticket.membership_id,ticket.target,ticket.target_path,ticket.expires_at,ticket.used_at,
                       session.status,session.user_auth_version,users.auth_version current_auth_version,
                       users.enabled,membership.status membership_status,organization.status organization_status
                from mobile_webview_tickets ticket
                join mobile_device_sessions session on session.id=ticket.session_id
                join user_accounts users on users.id=session.user_id
                join memberships membership on membership.id=ticket.membership_id
                join organizations organization on organization.id=ticket.organization_id
                where ticket.ticket_hash=? for update
                """, (rs, row) -> new TicketRow(
                rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getObject("membership_id", UUID.class),
                rs.getString("target"), rs.getString("target_path"), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant(),
                rs.getString("status"), rs.getLong("user_auth_version"), rs.getLong("current_auth_version"),
                rs.getBoolean("enabled"), rs.getString("membership_status"), rs.getString("organization_status")),
                sha256(ticket));
        if (rows.isEmpty()) throw ApiException.badRequest("MOBILE_WEB_TICKET_INVALID", "网页会话凭据无效");
        TicketRow row = rows.getFirst();
        if (row.usedAt() != null) throw ApiException.conflict("MOBILE_WEB_TICKET_REPLAYED", "网页会话凭据已使用");
        if (row.expiresAt().isBefore(now) || !"ACTIVE".equals(row.sessionStatus())
                || row.sessionAuthVersion() != row.currentAuthVersion() || !row.userEnabled()
                || !"ACTIVE".equals(row.membershipStatus()) || !"ACTIVE".equals(row.organizationStatus())) {
            throw ApiException.conflict("MOBILE_WEB_TICKET_EXPIRED", "网页会话凭据已过期或设备已撤销");
        }
        jdbc.update("update mobile_webview_tickets set used_at=? where id=?", Timestamp.from(now), row.id());
        CloudPrincipal principal = users.loadByMembershipId(row.membershipId());
        return new Exchange(principal, row.sessionId(), row.target(), row.targetPath());
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Exchange(CloudPrincipal principal, UUID sessionId, String target, String targetPath) {}
    private record TicketRow(UUID id, UUID sessionId, UUID membershipId, String target, String targetPath,
                             Instant expiresAt, Instant usedAt,
                             String sessionStatus, long sessionAuthVersion, long currentAuthVersion,
                             boolean userEnabled, String membershipStatus, String organizationStatus) {}
}
