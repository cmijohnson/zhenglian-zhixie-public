package com.yingge.cloud.mobile.application;

import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.auth.infrastructure.CloudUserDetailsService;
import com.yingge.cloud.mobile.api.MobileDtos;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class MobileAuthService {
    private static final Duration ACCESS_TTL = Duration.ofMinutes(15);
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final int MAX_ACTIVE_DEVICES = 5;

    private final JdbcTemplate jdbc;
    private final CloudUserDetailsService users;
    private final SecureRandom random = new SecureRandom();

    public MobileAuthService(JdbcTemplate jdbc, CloudUserDetailsService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Transactional
    public MobileDtos.TokenResponse login(CloudPrincipal principal, UUID installationId,
                                           MobileDtos.Platform platform, String appVersion,
                                           long versionCode, String deviceName) {
        requireMetadata(installationId, platform, appVersion, versionCode);
        Instant now = Instant.now();
        var actor = principal.actor();
        long authVersion = currentAuthVersion(actor.userId());
        List<UUID> existing = jdbc.queryForList("""
                select id from mobile_device_sessions
                where membership_id=? and installation_id=? for update
                """, UUID.class, actor.membershipId(), installationId.toString());
        UUID sessionId;
        UUID familyId = UUID.randomUUID();
        if (existing.isEmpty()) {
            sessionId = UUID.randomUUID();
            jdbc.update("""
                    insert into mobile_device_sessions
                    (id,user_id,membership_id,organization_id,installation_id,platform,device_name,
                     app_version,build_number,refresh_family_id,user_auth_version,status,created_at,last_seen_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)
                    """, sessionId, actor.userId(), actor.membershipId(), actor.organizationId(),
                    installationId.toString(), platform.name(), clean(deviceName, 160), clean(appVersion, 40),
                    versionCode, familyId, authVersion, Timestamp.from(now), Timestamp.from(now));
        } else {
            sessionId = existing.getFirst();
            revokeTokens(sessionId, now);
            jdbc.update("""
                    update mobile_device_sessions
                    set platform=?,device_name=?,app_version=?,build_number=?,refresh_family_id=?,
                        user_auth_version=?,status='ACTIVE',revoked_at=null,last_seen_at=?
                    where id=?
                    """, platform.name(), clean(deviceName, 160), clean(appVersion, 40), versionCode,
                    familyId, authVersion, Timestamp.from(now), sessionId);
        }
        trimOldDevices(actor.membershipId(), sessionId, now);
        return issuePair(sessionId, familyId, authVersion, principal, now);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public MobileDtos.TokenResponse refresh(String refreshToken, UUID installationId,
                                             MobileDtos.Platform platform, String appVersion, long versionCode) {
        requireMetadata(installationId, platform, appVersion, versionCode);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw unauthorized("MOBILE_REFRESH_TOKEN_INVALID", "刷新凭据无效或已过期");
        }
        Instant now = Instant.now();
        List<RefreshRow> rows = jdbc.query("""
                select refresh.id,refresh.session_id,refresh.family_id,refresh.expires_at,
                       refresh.consumed_at,refresh.revoked_at,session.user_id,session.membership_id,
                       session.installation_id,session.platform,session.status,session.user_auth_version,
                       users.auth_version current_auth_version,users.enabled user_enabled,
                       membership.status membership_status,organization.status organization_status
                from mobile_refresh_tokens refresh
                join mobile_device_sessions session on session.id=refresh.session_id
                join user_accounts users on users.id=session.user_id
                join memberships membership on membership.id=session.membership_id
                join organizations organization on organization.id=session.organization_id
                where refresh.token_hash=? for update
                """, (rs, row) -> new RefreshRow(
                rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getObject("family_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                rs.getObject("user_id", UUID.class), rs.getObject("membership_id", UUID.class),
                rs.getString("installation_id"), rs.getString("platform"), rs.getString("status"),
                rs.getLong("user_auth_version"), rs.getLong("current_auth_version"),
                rs.getBoolean("user_enabled"), rs.getString("membership_status"),
                rs.getString("organization_status")), sha256(refreshToken));
        if (rows.isEmpty()) throw unauthorized("MOBILE_REFRESH_TOKEN_INVALID", "刷新凭据无效或已过期");
        RefreshRow row = rows.getFirst();
        if (row.consumedAt() != null) {
            revokeSession(row.sessionId(), now);
            throw ApiException.conflict("MOBILE_REFRESH_REPLAYED", "检测到刷新凭据重放，该设备会话已撤销");
        }
        if (row.revokedAt() != null) {
            throw unauthorized("MOBILE_REFRESH_TOKEN_INVALID", "刷新凭据无效或已过期");
        }
        if (row.expiresAt().isBefore(now) || !"ACTIVE".equals(row.sessionStatus())
                || row.sessionAuthVersion() != row.currentAuthVersion() || !row.userEnabled()
                || !"ACTIVE".equals(row.membershipStatus()) || !"ACTIVE".equals(row.organizationStatus())) {
            revokeSession(row.sessionId(), now);
            throw unauthorized("MOBILE_REFRESH_TOKEN_INVALID", "刷新凭据无效或已过期");
        }
        if (!row.installationId().equals(installationId.toString()) || !row.platform().equals(platform.name())) {
            revokeSession(row.sessionId(), now);
            throw ApiException.conflict("MOBILE_DEVICE_BINDING_MISMATCH", "刷新凭据与当前设备不匹配");
        }
        jdbc.update("update mobile_refresh_tokens set consumed_at=? where id=?",
                Timestamp.from(now), row.id());
        jdbc.update("""
                update mobile_device_sessions set app_version=?,build_number=?,last_seen_at=? where id=?
                """, clean(appVersion, 40), versionCode, Timestamp.from(now), row.sessionId());
        CloudPrincipal principal;
        try {
            principal = users.loadByMembershipId(row.membershipId());
        } catch (RuntimeException exception) {
            revokeSession(row.sessionId(), now);
            throw unauthorized("MOBILE_REFRESH_TOKEN_INVALID", "账号或成员关系已失效");
        }
        MobileDtos.TokenResponse response = issuePair(row.sessionId(), row.familyId(),
                row.currentAuthVersion(), principal, now);
        UUID replacement = jdbc.queryForObject("""
                select id from mobile_refresh_tokens where session_id=? and family_id=?
                order by created_at desc limit 1
                """, UUID.class, row.sessionId(), row.familyId());
        jdbc.update("update mobile_refresh_tokens set replaced_by_token_id=? where id=?", replacement, row.id());
        return response;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public AccessAuthentication authenticateAccess(String rawToken, UUID installationId,
                                                     MobileDtos.Platform platform, String appVersion,
                                                     long versionCode) {
        requireMetadata(installationId, platform, appVersion, versionCode);
        if (rawToken == null || rawToken.isBlank()) {
            throw unauthorized("MOBILE_ACCESS_TOKEN_INVALID", "访问凭据无效或已过期");
        }
        Instant now = Instant.now();
        List<AccessAuthentication> rows = jdbc.query("""
                select access.id token_id,session.id session_id,session.membership_id,
                       session.installation_id,session.platform,session.status,session.user_auth_version,
                       users.auth_version current_auth_version
                from mobile_access_tokens access
                join mobile_device_sessions session on session.id=access.session_id
                join user_accounts users on users.id=session.user_id
                join memberships membership on membership.id=session.membership_id
                join organizations organization on organization.id=session.organization_id
                where access.token_hash=? and access.revoked_at is null and access.expires_at>?
                  and session.status='ACTIVE' and users.enabled=true and membership.status='ACTIVE'
                  and organization.status='ACTIVE'
                """, (rs, row) -> new AccessAuthentication(
                rs.getObject("token_id", UUID.class), rs.getObject("session_id", UUID.class),
                rs.getObject("membership_id", UUID.class), rs.getString("installation_id"),
                rs.getString("platform"),
                rs.getLong("user_auth_version"), rs.getLong("current_auth_version")),
                sha256(rawToken), Timestamp.from(now));
        if (rows.isEmpty()) throw unauthorized("MOBILE_ACCESS_TOKEN_INVALID", "访问凭据无效或已过期");
        AccessAuthentication authentication = rows.getFirst();
        if (authentication.sessionAuthVersion() != authentication.currentAuthVersion()) {
            revokeSession(authentication.sessionId(), now);
            throw unauthorized("MOBILE_ACCESS_TOKEN_INVALID", "账号凭据已变更，请重新登录");
        }
        if (!authentication.installationId().equals(installationId.toString())
                || !authentication.platform().equals(platform.name())) {
            throw ApiException.conflict("MOBILE_DEVICE_BINDING_MISMATCH", "访问凭据与当前设备不匹配");
        }
        jdbc.update("update mobile_access_tokens set last_used_at=? where id=? and (last_used_at is null or last_used_at<?)",
                Timestamp.from(now), authentication.tokenId(), Timestamp.from(now.minusSeconds(60)));
        jdbc.update("""
                update mobile_device_sessions set app_version=?,build_number=?,last_seen_at=?
                where id=? and last_seen_at<?
                """, clean(appVersion, 40), versionCode, Timestamp.from(now), authentication.sessionId(),
                Timestamp.from(now.minusSeconds(60)));
        return authentication;
    }

    @Transactional
    public void logout(UUID sessionId) {
        if (sessionId != null) revokeSession(sessionId, Instant.now());
    }

    @Transactional(readOnly = true)
    public List<MobileDtos.Device> devices(UUID userId, UUID currentSessionId) {
        return jdbc.query("""
                select id,platform,device_name,app_version,created_at,last_seen_at,revoked_at
                from mobile_device_sessions where user_id=? order by last_seen_at desc
                """, (rs, row) -> new MobileDtos.Device(
                rs.getObject("id", UUID.class), MobileDtos.Platform.valueOf(rs.getString("platform")),
                rs.getString("device_name"), rs.getString("app_version"),
                rs.getObject("id", UUID.class).equals(currentSessionId), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()), userId);
    }

    @Transactional
    public void revokeDevice(UUID userId, UUID deviceId) {
        List<UUID> ids = jdbc.queryForList("select id from mobile_device_sessions where id=? and user_id=? for update",
                UUID.class, deviceId, userId);
        if (ids.isEmpty()) throw ApiException.notFound("MOBILE_DEVICE_NOT_FOUND", "设备不存在");
        revokeSession(deviceId, Instant.now());
    }

    private MobileDtos.TokenResponse issuePair(UUID sessionId, UUID familyId, long authVersion,
                                                CloudPrincipal principal, Instant now) {
        String access = randomToken();
        String refresh = randomToken();
        Instant accessExpiry = now.plus(ACCESS_TTL);
        Instant refreshExpiry = now.plus(REFRESH_TTL);
        jdbc.update("""
                insert into mobile_access_tokens(id,session_id,token_hash,created_at,expires_at)
                values (?,?,?,?,?)
                """, UUID.randomUUID(), sessionId, sha256(access), Timestamp.from(now), Timestamp.from(accessExpiry));
        jdbc.update("""
                insert into mobile_refresh_tokens(id,session_id,family_id,token_hash,created_at,expires_at)
                values (?,?,?,?,?,?)
                """, UUID.randomUUID(), sessionId, familyId, sha256(refresh), Timestamp.from(now),
                Timestamp.from(refreshExpiry));
        return new MobileDtos.TokenResponse(access, accessExpiry, refresh, refreshExpiry,
                MobileDtos.Actor.from(principal));
    }

    private void trimOldDevices(UUID membershipId, UUID currentSessionId, Instant now) {
        List<UUID> stale = jdbc.queryForList("""
                select id from mobile_device_sessions
                where membership_id=? and status='ACTIVE' and id<>?
                order by last_seen_at desc offset ?
                """, UUID.class, membershipId, currentSessionId, MAX_ACTIVE_DEVICES - 1);
        stale.forEach(id -> revokeSession(id, now));
    }

    private void revokeSession(UUID sessionId, Instant now) {
        revokeTokens(sessionId, now);
        jdbc.update("""
                update mobile_device_sessions set status='REVOKED',revoked_at=?,last_seen_at=?
                where id=? and status<>'REVOKED'
                """, Timestamp.from(now), Timestamp.from(now), sessionId);
        jdbc.update("update mobile_push_registrations set enabled=false,invalidated_at=? where session_id=? and enabled=true",
                Timestamp.from(now), sessionId);
    }

    private void revokeTokens(UUID sessionId, Instant now) {
        jdbc.update("update mobile_access_tokens set revoked_at=? where session_id=? and revoked_at is null",
                Timestamp.from(now), sessionId);
        jdbc.update("update mobile_refresh_tokens set revoked_at=? where session_id=? and revoked_at is null",
                Timestamp.from(now), sessionId);
    }

    private long currentAuthVersion(UUID userId) {
        Long version = jdbc.queryForObject("select auth_version from user_accounts where id=?", Long.class, userId);
        return version == null ? 0 : version;
    }

    private void requireMetadata(UUID installationId, MobileDtos.Platform platform, String appVersion, long versionCode) {
        if (installationId == null || platform == null || appVersion == null || appVersion.isBlank()
                || appVersion.length() > 64 || versionCode < 1) {
            throw ApiException.badRequest("MOBILE_DEVICE_METADATA_INVALID", "移动设备信息不完整");
        }
    }

    private String clean(String value, int max) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("MOBILE_DEVICE_METADATA_INVALID", "移动设备信息不完整");
        String normalized = value.trim();
        if (normalized.length() > max) throw ApiException.badRequest("MOBILE_DEVICE_METADATA_INVALID", "移动设备信息过长");
        return normalized;
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

    private ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message);
    }

    public record AccessAuthentication(UUID tokenId, UUID sessionId, UUID membershipId, String installationId,
                                       String platform,
                                       long sessionAuthVersion, long currentAuthVersion) {}

    private record RefreshRow(UUID id, UUID sessionId, UUID familyId, Instant expiresAt, Instant consumedAt,
                              Instant revokedAt, UUID userId, UUID membershipId, String installationId,
                              String platform, String sessionStatus, long sessionAuthVersion,
                              long currentAuthVersion, boolean userEnabled, String membershipStatus,
                              String organizationStatus) {}
}
