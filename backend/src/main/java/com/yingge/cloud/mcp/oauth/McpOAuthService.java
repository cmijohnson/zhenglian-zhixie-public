package com.yingge.cloud.mcp.oauth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.mcp.McpProperties;
import com.yingge.cloud.permission.domain.CurrentActor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class McpOAuthService {
    public static final Set<String> SUPPORTED_SCOPES = Set.of(
            "yingge.read", "yingge.write", "yingge.ai", "yingge.admin.read", "yingge.admin");
    private static final String PERSONAL_CLIENT_ID = "yingge_qwenpaw_personal";
    private static final String PERSONAL_CLIENT_NAME = "问候个人访问令牌";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final McpProperties properties;
    private final CurrentActorProvider actors;
    private final AuditService audit;

    public McpOAuthService(JdbcTemplate jdbc, ObjectMapper mapper, McpProperties properties,
                           CurrentActorProvider actors, AuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
        this.actors = actors;
        this.audit = audit;
    }

    @Transactional
    public ClientRegistration register(ClientRegistrationRequest request) {
        String name = requireText(request.clientName(), "invalid_client_metadata", "client_name 不能为空", 200);
        List<String> redirects = request.redirectUris() == null ? List.of() : request.redirectUris().stream()
                .map(this::validateRedirectUri).distinct().toList();
        if (redirects.isEmpty() || redirects.size() > 10) {
            throw new OAuthProtocolException("invalid_redirect_uri", "redirect_uris 必须包含 1 到 10 个地址");
        }
        List<String> grants = request.grantTypes() == null || request.grantTypes().isEmpty()
                ? List.of("authorization_code", "refresh_token") : List.copyOf(request.grantTypes());
        if (!Set.of("authorization_code", "refresh_token").containsAll(grants)
                || !grants.contains("authorization_code")) {
            throw new OAuthProtocolException("invalid_client_metadata", "仅支持 authorization_code 和 refresh_token");
        }
        List<String> responses = request.responseTypes() == null || request.responseTypes().isEmpty()
                ? List.of("code") : List.copyOf(request.responseTypes());
        if (!responses.equals(List.of("code"))) {
            throw new OAuthProtocolException("invalid_client_metadata", "response_types 仅支持 code");
        }
        String authMethod = request.tokenEndpointAuthMethod() == null
                ? "none" : request.tokenEndpointAuthMethod().trim();
        if (!"none".equals(authMethod)) {
            throw new OAuthProtocolException("invalid_client_metadata", "动态注册客户端必须使用 PKCE，不签发 client_secret");
        }
        String clientId = "yg_" + randomToken(24);
        Instant now = Instant.now();
        jdbc.update("""
                insert into oauth_clients
                    (id, client_id, client_name, redirect_uris, grant_types, response_types,
                     token_endpoint_auth_method, active, created_at)
                values (?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), 'none', true, ?)
                """, UUID.randomUUID(), clientId, name, json(redirects), json(grants), json(responses), Timestamp.from(now));
        return new ClientRegistration(clientId, name, redirects, grants, responses, "none",
                now.getEpochSecond());
    }

    @Transactional
    public UUID beginAuthorization(String responseType, String clientId, String redirectUri, String scope,
                                   String state, String codeChallenge, String codeChallengeMethod,
                                   String resource) {
        if (!"code".equals(responseType)) {
            throw new OAuthProtocolException("unsupported_response_type", "仅支持 response_type=code");
        }
        ClientRow client = requireClient(clientId);
        String validatedRedirect = validateRedirectUri(redirectUri);
        if (!client.redirectUris().contains(validatedRedirect)) {
            throw new OAuthProtocolException("invalid_request", "redirect_uri 与客户端注册信息不匹配");
        }
        if (!"S256".equals(codeChallengeMethod) || codeChallenge == null
                || !codeChallenge.matches("[A-Za-z0-9._~-]{43,128}")) {
            throw new OAuthProtocolException("invalid_request", "必须使用有效的 PKCE S256 code_challenge");
        }
        String requestedResource = resource == null || resource.isBlank()
                ? properties.normalizedResourceUri() : resource;
        if (!properties.normalizedResourceUri().equals(requestedResource)) {
            throw new OAuthProtocolException("invalid_target", "resource 必须精确指向本 MCP 服务");
        }
        Set<String> scopes = parseScopes(scope);
        if (state != null && state.length() > 1000) {
            throw new OAuthProtocolException("invalid_request", "state 过长");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update("""
                insert into oauth_authorization_requests
                    (id, client_id, redirect_uri, state, code_challenge, resource_uri,
                     requested_scopes, status, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), 'PENDING', ?, ?)
                """, id, client.clientId(), validatedRedirect, state, codeChallenge, requestedResource,
                json(scopes), Timestamp.from(now), Timestamp.from(now.plus(properties.authorizationRequestTtl())));
        return id;
    }

    @Transactional(readOnly = true)
    public ConsentView consent(UUID requestId) {
        CurrentActor actor = actors.require();
        AuthorizationRequestRow row = requirePendingRequest(requestId, false);
        Set<String> granted = grantableScopes(actor, row.requestedScopes());
        return new ConsentView(row.id(), row.clientId(), row.clientName(), row.redirectUri(),
                row.requestedScopes(), granted, row.expiresAt());
    }

    @Transactional
    public AuthorizationDecision approve(UUID requestId) {
        CurrentActor actor = actors.require();
        AuthorizationRequestRow row = requirePendingRequest(requestId, true);
        Set<String> scopes = grantableScopes(actor, row.requestedScopes());
        if (scopes.isEmpty()) {
            throw new OAuthProtocolException("invalid_scope", "当前账号没有可授权的 Scope");
        }
        Instant now = Instant.now();
        jdbc.update("""
                update oauth_grants set status='REVOKED', revoked_at=?
                where client_id=? and membership_id=? and status='ACTIVE'
                """, Timestamp.from(now), row.clientId(), actor.membershipId());
        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                insert into oauth_grants
                    (id, client_id, user_id, membership_id, organization_id, resource_uri, scopes,
                     status, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), 'ACTIVE', ?, ?)
                """, grantId, row.clientId(), actor.userId(), actor.membershipId(), actor.organizationId(),
                row.resourceUri(), json(scopes), Timestamp.from(now),
                Timestamp.from(now.plus(properties.refreshTokenTtl())));
        String code = randomToken(32);
        jdbc.update("""
                insert into oauth_authorization_codes
                    (id, code_hash, authorization_request_id, grant_id, redirect_uri,
                     code_challenge, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), sha256(code), row.id(), grantId, row.redirectUri(),
                row.codeChallenge(), Timestamp.from(now.plus(properties.authorizationCodeTtl())));
        jdbc.update("""
                update oauth_authorization_requests
                set status='APPROVED', user_id=?, membership_id=?, organization_id=?, decided_at=?
                where id=?
                """, actor.userId(), actor.membershipId(), actor.organizationId(), Timestamp.from(now), row.id());
        jdbc.update("update oauth_clients set last_used_at=? where client_id=?", Timestamp.from(now), row.clientId());
        audit.record(actor, "MCP_OAUTH_GRANTED", "OAUTH_GRANT", grantId.toString(), "SUCCESS",
                Map.of("clientId", row.clientId(), "scopes", scopes));
        return new AuthorizationDecision(redirect(row.redirectUri(), Map.of("code", code), row.state()), grantId);
    }

    @Transactional
    public AuthorizationDecision deny(UUID requestId) {
        CurrentActor actor = actors.require();
        AuthorizationRequestRow row = requirePendingRequest(requestId, true);
        jdbc.update("update oauth_authorization_requests set status='DENIED', decided_at=now() where id=?", row.id());
        audit.record(actor, "MCP_OAUTH_DENIED", "OAUTH_CLIENT", row.clientId(), "SUCCESS", null);
        return new AuthorizationDecision(redirect(row.redirectUri(), Map.of("error", "access_denied"), row.state()), null);
    }

    @Transactional(noRollbackFor = OAuthProtocolException.class)
    public TokenResponse token(Map<String, String> form) {
        return switch (String.valueOf(form.get("grant_type"))) {
            case "authorization_code" -> exchangeAuthorizationCode(form);
            case "refresh_token" -> rotateRefreshToken(form);
            default -> throw new OAuthProtocolException("unsupported_grant_type", "仅支持 authorization_code 和 refresh_token");
        };
    }

    @Transactional
    public void revokeToken(String clientId, String token) {
        requireClient(clientId);
        if (token == null || token.isBlank()) return;
        String hash = sha256(token);
        jdbc.update("""
                update oauth_access_tokens access set revoked_at=now()
                from oauth_grants grant_row
                where access.grant_id=grant_row.id and grant_row.client_id=?
                  and access.token_hash=? and access.revoked_at is null
                """, clientId, hash);
        jdbc.update("""
                update oauth_refresh_tokens refresh set revoked_at=now()
                from oauth_grants grant_row
                where refresh.grant_id=grant_row.id and grant_row.client_id=?
                  and refresh.token_hash=? and refresh.revoked_at is null
                """, clientId, hash);
    }

    @Transactional
    public TokenAuthentication authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new OAuthProtocolException("invalid_token", "Bearer Token 缺失",
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        Instant now = Instant.now();
        TokenAuthentication token = jdbc.query("""
                select access.id token_id, access.grant_id, grant_row.client_id, grant_row.user_id,
                       grant_row.membership_id, grant_row.organization_id, grant_row.scopes,
                       access.user_auth_version, users.auth_version current_auth_version
                from oauth_access_tokens access
                join oauth_grants grant_row on grant_row.id=access.grant_id
                join user_accounts users on users.id=grant_row.user_id
                join memberships membership on membership.id=grant_row.membership_id
                join organizations organization on organization.id=grant_row.organization_id
                where access.token_hash=? and access.revoked_at is null and access.expires_at>?
                  and grant_row.status='ACTIVE' and grant_row.expires_at>?
                  and grant_row.resource_uri=?
                  and users.enabled=true and membership.status='ACTIVE' and organization.status='ACTIVE'
                """, (result, index) -> new TokenAuthentication(
                result.getObject("token_id", UUID.class), result.getObject("grant_id", UUID.class),
                result.getString("client_id"), result.getObject("user_id", UUID.class),
                result.getObject("membership_id", UUID.class), result.getObject("organization_id", UUID.class),
                Set.copyOf(readList(result.getString("scopes"))), result.getLong("user_auth_version"),
                result.getLong("current_auth_version")), sha256(rawToken), Timestamp.from(now), Timestamp.from(now),
                properties.normalizedResourceUri())
                .stream().findFirst().orElseThrow(() -> new OAuthProtocolException(
                        "invalid_token", "Bearer Token 无效或已过期", org.springframework.http.HttpStatus.UNAUTHORIZED));
        if (token.userAuthVersion() != token.currentAuthVersion()) {
            throw new OAuthProtocolException("invalid_token", "账号凭据已变更，请重新授权",
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        jdbc.update("""
                update oauth_access_tokens set last_used_at=?
                where id=? and (last_used_at is null or last_used_at<?)
                """, Timestamp.from(now), token.tokenId(), Timestamp.from(now.minusSeconds(60)));
        jdbc.update("update oauth_grants set last_used_at=? where id=?", Timestamp.from(now), token.grantId());
        return token;
    }

    @Transactional(readOnly = true)
    public List<GrantView> ownGrants() {
        CurrentActor actor = actors.require();
        return grantViews("grant_row.membership_id=?", actor.membershipId());
    }

    @Transactional
    public PersonalToken issuePersonalToken() {
        CurrentActor actor = actors.require();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.refreshTokenTtl());
        Set<String> scopes = grantableScopes(actor, SUPPORTED_SCOPES);

        jdbc.update("""
                insert into oauth_clients
                    (id, client_id, client_name, redirect_uris, grant_types, response_types,
                     token_endpoint_auth_method, active, created_at)
                values (?, ?, ?, cast('[]' as jsonb), cast('[]' as jsonb), cast('[]' as jsonb),
                        'none', true, ?)
                on conflict (client_id) do update set active=true, client_name=excluded.client_name
                """, UUID.randomUUID(), PERSONAL_CLIENT_ID, PERSONAL_CLIENT_NAME, Timestamp.from(now));

        List<UUID> previousGrants = jdbc.queryForList("""
                select id from oauth_grants
                where client_id=? and membership_id=? and status='ACTIVE'
                for update
                """, UUID.class, PERSONAL_CLIENT_ID, actor.membershipId());
        for (UUID grantId : previousGrants) revokeGrantRows(grantId, now);

        UUID grantId = UUID.randomUUID();
        jdbc.update("""
                insert into oauth_grants
                    (id, client_id, user_id, membership_id, organization_id, resource_uri, scopes,
                     status, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), 'ACTIVE', ?, ?)
                """, grantId, PERSONAL_CLIENT_ID, actor.userId(), actor.membershipId(), actor.organizationId(),
                properties.normalizedResourceUri(), json(scopes), Timestamp.from(now), Timestamp.from(expiresAt));

        Long authVersion = jdbc.queryForObject(
                "select auth_version from user_accounts where id=?", Long.class, actor.userId());
        String rawToken = randomToken(48);
        jdbc.update("""
                insert into oauth_access_tokens
                    (id, token_hash, grant_id, user_auth_version, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), sha256(rawToken), grantId, authVersion == null ? 0L : authVersion,
                Timestamp.from(now), Timestamp.from(expiresAt));
        audit.record(actor, "MCP_PERSONAL_TOKEN_ISSUED", "OAUTH_GRANT", grantId.toString(), "SUCCESS",
                Map.of("clientId", PERSONAL_CLIENT_ID, "scopes", scopes, "expiresAt", expiresAt.toString()));
        return new PersonalToken(grantId, PERSONAL_CLIENT_ID, rawToken, scopes,
                properties.normalizedResourceUri(), expiresAt);
    }

    @Transactional(readOnly = true)
    public List<GrantView> organizationGrants() {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin()) throw new com.yingge.cloud.shared.api.ApiException(
                org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN", "仅组织管理员可查看 Agent 授权");
        return grantViews("grant_row.organization_id=?", actor.organizationId());
    }

    @Transactional
    public void revokeGrant(UUID grantId, boolean administrator) {
        CurrentActor actor = actors.require();
        int changed = administrator && actor.isAdmin()
                ? jdbc.update("""
                    update oauth_grants set status='REVOKED', revoked_at=now()
                    where id=? and organization_id=? and status='ACTIVE'
                    """, grantId, actor.organizationId())
                : jdbc.update("""
                    update oauth_grants set status='REVOKED', revoked_at=now()
                    where id=? and membership_id=? and status='ACTIVE'
                    """, grantId, actor.membershipId());
        if (changed == 0) throw com.yingge.cloud.shared.api.ApiException.notFound(
                "MCP_GRANT_NOT_FOUND", "Agent 授权不存在或已撤销");
        jdbc.update("update oauth_access_tokens set revoked_at=now() where grant_id=? and revoked_at is null", grantId);
        jdbc.update("update oauth_refresh_tokens set revoked_at=now() where grant_id=? and revoked_at is null", grantId);
        audit.record(actor, "MCP_OAUTH_REVOKED", "OAUTH_GRANT", grantId.toString(), "SUCCESS", null);
    }

    private void revokeGrantRows(UUID grantId, Instant revokedAt) {
        Timestamp timestamp = Timestamp.from(revokedAt);
        jdbc.update("update oauth_grants set status='REVOKED', revoked_at=? where id=? and status='ACTIVE'",
                timestamp, grantId);
        jdbc.update("update oauth_access_tokens set revoked_at=? where grant_id=? and revoked_at is null",
                timestamp, grantId);
        jdbc.update("update oauth_refresh_tokens set revoked_at=? where grant_id=? and revoked_at is null",
                timestamp, grantId);
    }

    private TokenResponse exchangeAuthorizationCode(Map<String, String> form) {
        String clientId = form.get("client_id");
        requireClient(clientId);
        String code = form.get("code");
        String redirectUri = form.get("redirect_uri");
        String verifier = form.get("code_verifier");
        if (code == null || verifier == null || redirectUri == null) {
            throw new OAuthProtocolException("invalid_request", "code、redirect_uri 和 code_verifier 为必填项");
        }
        Instant now = Instant.now();
        CodeRow row = jdbc.query("""
                select auth_code.id, auth_code.grant_id, auth_code.redirect_uri, auth_code.code_challenge,
                       grant_row.client_id, grant_row.user_id, grant_row.membership_id,
                       grant_row.organization_id, grant_row.resource_uri, grant_row.scopes, users.auth_version
                from oauth_authorization_codes auth_code
                join oauth_grants grant_row on grant_row.id=auth_code.grant_id
                join user_accounts users on users.id=grant_row.user_id
                where auth_code.code_hash=? and auth_code.consumed_at is null and auth_code.expires_at>?
                  and grant_row.status='ACTIVE' and grant_row.resource_uri=?
                for update
                """, (result, index) -> new CodeRow(
                result.getObject("id", UUID.class), result.getObject("grant_id", UUID.class),
                result.getString("redirect_uri"), result.getString("code_challenge"), result.getString("client_id"),
                result.getObject("user_id", UUID.class), result.getObject("membership_id", UUID.class),
                result.getObject("organization_id", UUID.class), result.getString("resource_uri"),
                Set.copyOf(readList(result.getString("scopes"))), result.getLong("auth_version")),
                sha256(code), Timestamp.from(now), properties.normalizedResourceUri())
                .stream().findFirst().orElseThrow(() -> new OAuthProtocolException("invalid_grant", "授权码无效、已过期或已使用"));
        if (!row.clientId().equals(clientId) || !row.redirectUri().equals(validateRedirectUri(redirectUri))
                || !constantTimeEquals(row.codeChallenge(), pkceChallenge(verifier))) {
            throw new OAuthProtocolException("invalid_grant", "客户端、回调地址或 PKCE 校验失败");
        }
        jdbc.update("update oauth_authorization_codes set consumed_at=? where id=?", Timestamp.from(now), row.id());
        return issueTokens(row.grantId(), row.clientId(), row.scopes(), row.authVersion(), now);
    }

    private TokenResponse rotateRefreshToken(Map<String, String> form) {
        String clientId = form.get("client_id");
        requireClient(clientId);
        String raw = form.get("refresh_token");
        if (raw == null || raw.isBlank()) throw new OAuthProtocolException("invalid_request", "refresh_token 为必填项");
        Instant now = Instant.now();
        RefreshRow row = jdbc.query("""
                select refresh.id, refresh.grant_id, refresh.user_auth_version, grant_row.client_id,
                       grant_row.resource_uri, grant_row.scopes, users.auth_version current_auth_version
                from oauth_refresh_tokens refresh
                join oauth_grants grant_row on grant_row.id=refresh.grant_id
                join user_accounts users on users.id=grant_row.user_id
                join memberships membership on membership.id=grant_row.membership_id
                where refresh.token_hash=? and refresh.consumed_at is null and refresh.revoked_at is null
                  and refresh.expires_at>? and grant_row.status='ACTIVE' and grant_row.expires_at>?
                  and grant_row.resource_uri=?
                  and users.enabled=true and membership.status='ACTIVE'
                for update
                """, (result, index) -> new RefreshRow(
                result.getObject("id", UUID.class), result.getObject("grant_id", UUID.class),
                result.getLong("user_auth_version"), result.getString("client_id"), result.getString("resource_uri"),
                Set.copyOf(readList(result.getString("scopes"))), result.getLong("current_auth_version")),
                sha256(raw), Timestamp.from(now), Timestamp.from(now), properties.normalizedResourceUri())
                .stream().findFirst().orElse(null);
        if (row == null) {
            revokeRefreshFamilyOnReplay(clientId, raw);
            throw new OAuthProtocolException("invalid_grant", "Refresh Token 无效、已过期或已使用");
        }
        if (!row.clientId().equals(clientId) || row.userAuthVersion() != row.currentAuthVersion()) {
            throw new OAuthProtocolException("invalid_grant", "Refresh Token 与客户端不匹配或账号凭据已变更");
        }
        jdbc.update("update oauth_refresh_tokens set consumed_at=? where id=?", Timestamp.from(now), row.id());
        TokenResponse response = issueTokens(row.grantId(), row.clientId(), row.scopes(), row.currentAuthVersion(), now);
        jdbc.update("update oauth_refresh_tokens set replaced_by_token_id=? where id=?", response.refreshTokenId(), row.id());
        return response;
    }

    private TokenResponse issueTokens(UUID grantId, String clientId, Set<String> scopes, long authVersion, Instant now) {
        String access = randomToken(48);
        String refresh = randomToken(48);
        UUID refreshId = UUID.randomUUID();
        jdbc.update("""
                insert into oauth_access_tokens
                    (id, token_hash, grant_id, user_auth_version, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), sha256(access), grantId, authVersion, Timestamp.from(now),
                Timestamp.from(now.plus(properties.accessTokenTtl())));
        jdbc.update("""
                insert into oauth_refresh_tokens
                    (id, token_hash, grant_id, user_auth_version, created_at, expires_at)
                values (?, ?, ?, ?, ?, ?)
                """, refreshId, sha256(refresh), grantId, authVersion, Timestamp.from(now),
                Timestamp.from(now.plus(properties.refreshTokenTtl())));
        return new TokenResponse(access, "Bearer", properties.accessTokenTtl().toSeconds(), refresh,
                String.join(" ", scopes), properties.normalizedResourceUri(), refreshId);
    }

    private List<GrantView> grantViews(String condition, UUID value) {
        return jdbc.query("""
                select grant_row.id, grant_row.client_id, client.client_name, grant_row.scopes,
                       users.display_name, grant_row.status, grant_row.created_at,
                       grant_row.expires_at, grant_row.last_used_at, grant_row.revoked_at
                from oauth_grants grant_row
                join oauth_clients client on client.client_id=grant_row.client_id
                join user_accounts users on users.id=grant_row.user_id
                """ + " where " + condition + " order by grant_row.created_at desc", (result, index) -> new GrantView(
                result.getObject("id", UUID.class), result.getString("client_id"), result.getString("client_name"),
                result.getString("display_name"), Set.copyOf(readList(result.getString("scopes"))),
                result.getString("status"), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("expires_at").toInstant(), instant(result.getTimestamp("last_used_at")),
                instant(result.getTimestamp("revoked_at"))), value);
    }

    private ClientRow requireClient(String clientId) {
        if (clientId == null || clientId.isBlank()) throw new OAuthProtocolException("invalid_client", "client_id 缺失");
        return jdbc.query("""
                select client_id, client_name, redirect_uris from oauth_clients
                where client_id=? and active=true
                """, (result, index) -> new ClientRow(result.getString("client_id"), result.getString("client_name"),
                readList(result.getString("redirect_uris"))), clientId).stream().findFirst()
                .orElseThrow(() -> new OAuthProtocolException("invalid_client", "客户端不存在或已停用"));
    }

    private AuthorizationRequestRow requirePendingRequest(UUID id, boolean lock) {
        Instant now = Instant.now();
        String suffix = lock ? " for update" : "";
        AuthorizationRequestRow row = jdbc.query("""
                select request.id, request.client_id, client.client_name, request.redirect_uri,
                       request.state, request.code_challenge, request.resource_uri, request.requested_scopes,
                       request.status, request.expires_at
                from oauth_authorization_requests request
                join oauth_clients client on client.client_id=request.client_id and client.active=true
                where request.id=?
                """ + suffix, (result, index) -> new AuthorizationRequestRow(
                result.getObject("id", UUID.class), result.getString("client_id"), result.getString("client_name"),
                result.getString("redirect_uri"), result.getString("state"), result.getString("code_challenge"),
                result.getString("resource_uri"),
                Set.copyOf(readList(result.getString("requested_scopes"))), result.getString("status"),
                result.getTimestamp("expires_at").toInstant()), id).stream().findFirst()
                .orElseThrow(() -> new OAuthProtocolException("invalid_request", "授权请求不存在"));
        if (!"PENDING".equals(row.status()) || !row.expiresAt().isAfter(now)) {
            if ("PENDING".equals(row.status())) {
                jdbc.update("update oauth_authorization_requests set status='EXPIRED' where id=?", id);
            }
            throw new OAuthProtocolException("invalid_request", "授权请求已处理或过期");
        }
        return row;
    }

    private Set<String> parseScopes(String raw) {
        Set<String> scopes = new LinkedHashSet<>();
        if (raw != null) for (String value : raw.trim().split("\\s+")) if (!value.isBlank()) scopes.add(value);
        if (scopes.isEmpty()) scopes.add("yingge.read");
        if (!SUPPORTED_SCOPES.containsAll(scopes)) {
            throw new OAuthProtocolException("invalid_scope", "请求包含不受支持的 Scope");
        }
        return Set.copyOf(scopes);
    }

    private Set<String> grantableScopes(CurrentActor actor, Set<String> requested) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add("yingge.read");
        if (actor.canWriteCustomers()) allowed.add("yingge.write");
        if (actor.roles().stream().anyMatch(role -> !"VIEWER".equals(role.name()))) allowed.add("yingge.ai");
        if (actor.isAdmin()) {
            allowed.add("yingge.admin.read");
            allowed.add("yingge.admin");
        }
        allowed.retainAll(requested);
        return Set.copyOf(allowed);
    }

    private String validateRedirectUri(String raw) {
        try {
            if (raw == null || raw.length() > 1000) throw new IllegalArgumentException();
            URI uri = URI.create(raw);
            if (uri.getScheme() == null || uri.getHost() == null || uri.getFragment() != null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException();
            }
            String normalized = uri.toASCIIString();
            boolean http = "http".equalsIgnoreCase(uri.getScheme());
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if (!http && !https) throw new IllegalArgumentException();
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new OAuthProtocolException("invalid_redirect_uri",
                    "回调地址必须为格式合法的 HTTP 或 HTTPS URI");
        }
    }

    private void revokeRefreshFamilyOnReplay(String clientId, String rawToken) {
        String hash = sha256(rawToken);
        List<UUID> grants = jdbc.queryForList("""
                select refresh.grant_id from oauth_refresh_tokens refresh
                join oauth_grants grant_row on grant_row.id=refresh.grant_id
                where refresh.token_hash=? and grant_row.client_id=? and refresh.consumed_at is not null
                """, UUID.class, hash, clientId);
        for (UUID grantId : grants) {
            jdbc.update("update oauth_grants set status='REVOKED', revoked_at=now() where id=?", grantId);
            jdbc.update("update oauth_access_tokens set revoked_at=now() where grant_id=? and revoked_at is null", grantId);
            jdbc.update("update oauth_refresh_tokens set revoked_at=now() where grant_id=? and revoked_at is null", grantId);
        }
    }

    private String redirect(String base, Map<String, String> values, String state) {
        Map<String, String> params = new LinkedHashMap<>(values);
        if (state != null) params.put("state", state);
        StringBuilder result = new StringBuilder(base).append(base.contains("?") ? '&' : '?');
        params.forEach((key, value) -> result.append(url(key)).append('=').append(url(value)).append('&'));
        return result.substring(0, result.length() - 1);
    }

    private String url(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String requireText(String value, String code, String message, int max) {
        if (value == null || value.isBlank() || value.length() > max) throw new OAuthProtocolException(code, message);
        return value.trim();
    }

    private String pkceChallenge(String verifier) {
        if (verifier == null || !verifier.matches("[A-Za-z0-9._~-]{43,128}")) return "invalid";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier));
    }

    private boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }

    private String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String sha256(String value) { return HexFormat.of().formatHex(digest(value)); }
    private byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private List<String> readList(String value) {
        try { return value == null ? List.of() : mapper.readValue(value, STRING_LIST); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record ClientRegistrationRequest(String clientName, List<String> redirectUris,
                                            List<String> grantTypes, List<String> responseTypes,
                                            String tokenEndpointAuthMethod) {}
    public record ClientRegistration(String clientId, String clientName, List<String> redirectUris,
                                     List<String> grantTypes, List<String> responseTypes,
                                     String tokenEndpointAuthMethod, long clientIdIssuedAt) {}
    public record ConsentView(UUID requestId, String clientId, String clientName, String redirectUri,
                              Set<String> requestedScopes, Set<String> grantableScopes, Instant expiresAt) {}
    public record AuthorizationDecision(String redirectUrl, UUID grantId) {}
    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken,
                                String scope, String resource, UUID refreshTokenId) {}
    public record TokenAuthentication(UUID tokenId, UUID grantId, String clientId, UUID userId,
                                      UUID membershipId, UUID organizationId, Set<String> scopes,
                                      long userAuthVersion, long currentAuthVersion) {}
    public record PersonalToken(UUID grantId, String clientId, String accessToken, Set<String> scopes,
                                String resource, Instant expiresAt) {}
    public record GrantView(UUID id, String clientId, String clientName, String displayName, Set<String> scopes,
                            String status, Instant createdAt, Instant expiresAt, Instant lastUsedAt,
                            Instant revokedAt) {}
    private record ClientRow(String clientId, String clientName, List<String> redirectUris) {}
    private record AuthorizationRequestRow(UUID id, String clientId, String clientName, String redirectUri,
                                           String state, String codeChallenge, String resourceUri,
                                           Set<String> requestedScopes,
                                           String status, Instant expiresAt) {}
    private record CodeRow(UUID id, UUID grantId, String redirectUri, String codeChallenge, String clientId,
                           UUID userId, UUID membershipId, UUID organizationId, String resourceUri, Set<String> scopes,
                           long authVersion) {}
    private record RefreshRow(UUID id, UUID grantId, long userAuthVersion, String clientId, String resourceUri,
                              Set<String> scopes, long currentAuthVersion) {}
}
