package com.yingge.cloud.feishu.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.auth.infrastructure.CloudUserDetailsService;
import com.yingge.cloud.feishu.api.FeishuAuthDtos;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FeishuAuthService {
    public static final String CONFIRMATION_COOKIE = "FEISHU_BIND_INTENT";
    private static final String RECORD_CHANGE_SUBSCRIPTION_SCOPE = "docs:event:subscribe";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final FeishuIntegrationService integrations;
    private final FeishuBitableClient client;
    private final CloudUserDetailsService users;
    private final AuthenticationManager authenticationManager;
    private final PermissionService permissions;
    private final AuditService audit;
    private final String redirectUri;
    private final boolean allowInsecurePilot;

    public FeishuAuthService(JdbcTemplate jdbc, FeishuIntegrationService integrations, FeishuBitableClient client,
                             CloudUserDetailsService users, AuthenticationManager authenticationManager,
                             PermissionService permissions, AuditService audit,
                             @Value("${app.feishu.oauth-redirect-uri:}") String redirectUri,
                             @Value("${app.feishu.allow-insecure-oauth-pilot:false}") boolean allowInsecurePilot) {
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.client = client;
        this.users = users;
        this.authenticationManager = authenticationManager;
        this.permissions = permissions;
        this.audit = audit;
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
        this.allowInsecurePilot = allowInsecurePilot;
    }

    @Transactional(readOnly = true)
    public FeishuAuthDtos.LoginOptionsResponse options() {
        List<FeishuAuthDtos.LoginOption> options = jdbc.query("""
                select integration.id,organization.name
                from feishu_bitable_integrations integration
                join organizations organization on organization.id=integration.organization_id
                where integration.oauth_enabled=true and organization.status='ACTIVE'
                order by organization.name
                """, (rs, index) -> new FeishuAuthDtos.LoginOption(
                rs.getObject(1, UUID.class), rs.getString(2)));
        boolean transportReady = oauthTransportReady();
        return new FeishuAuthDtos.LoginOptionsResponse(transportReady && !options.isEmpty(), options,
                !redirectUri.startsWith("https://"));
    }

    @Transactional
    public StartResult start(String purposeValue, UUID integrationId, String returnTo) {
        Purpose purpose = Purpose.parse(purposeValue);
        CloudPrincipal principal = optionalPrincipal();
        if (purpose.requiresSession() && principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "授权飞书前请先登录英格云");
        }
        if (purpose == Purpose.SUBSCRIBE) permissions.requireAdmin(principal.actor());
        FeishuIntegrationService.IntegrationRow integration = selectIntegration(purpose, integrationId, principal);
        requireOAuthTransport();
        String state = randomToken();
        UUID intentId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(5, ChronoUnit.MINUTES);
        jdbc.update("""
                insert into feishu_oauth_intents
                (id,integration_id,purpose,membership_id,state_hash,return_to,expires_at)
                values (?,?,?,?,?,?,?)
                """, intentId, integration.id(), purpose.name(), principal == null ? null : principal.actor().membershipId(),
                sha256(state), safeReturnTo(returnTo, purpose), Timestamp.from(expiresAt));
        UriComponentsBuilder authorize = UriComponentsBuilder
                .fromUriString("https://accounts.feishu.cn/open-apis/authen/v1/authorize")
                .queryParam("app_id", integration.appId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", state);
        if (purpose == Purpose.SUBSCRIBE) {
            authorize.queryParam("scope", RECORD_CHANGE_SUBSCRIPTION_SCOPE);
        }
        String authorizeUrl = authorize.build().encode().toUriString();
        return new StartResult(authorizeUrl, intentId);
    }

    @Transactional
    public CallbackResult callback(String state, String code) {
        String stateHash = sha256(required(state, "FEISHU_STATE_INVALID", "飞书登录状态无效"));
        IntentRow intent = jdbc.query("""
                select * from feishu_oauth_intents where state_hash=? for update
                """, intentMapper(), stateHash).stream().findFirst()
                .orElseThrow(() -> ApiException.badRequest("FEISHU_STATE_INVALID", "飞书登录状态无效或已过期"));
        if (intent.consumedAt() != null || intent.callbackCompletedAt() != null || intent.expiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict("FEISHU_OAUTH_REPLAYED", "该飞书授权已使用或过期，请重新发起");
        }
        FeishuIntegrationService.IntegrationRow integration = integrations.findById(intent.integrationId());
        if (integration == null || intent.purpose() != Purpose.SUBSCRIBE && !integration.oauthEnabled()) {
            throw ApiException.conflict("FEISHU_LOGIN_DISABLED", "飞书快捷登录当前未启用");
        }
        if (intent.purpose() == Purpose.SUBSCRIBE
                && (!integration.active() || !integration.inboundSyncEnabled() || !"READY".equals(integration.schemaState()))) {
            throw ApiException.conflict("FEISHU_INBOUND_NOT_READY", "请先启用同步、安全双向协同并完成表结构校验");
        }
        String authorizationCode = required(code, "FEISHU_CODE_REQUIRED", "飞书授权码缺失");
        FeishuBitableClient.FeishuIdentity identity = intent.purpose() == Purpose.SUBSCRIBE
                ? client.authorizeRecordChangeSubscription(integrations.credentialsFor(integration),
                        authorizationCode, integration.baseAppToken())
                : client.exchangeIdentity(integrations.credentialsFor(integration), authorizationCode);
        integrations.rememberTenant(integration.id(), identity.tenantKey());
        jdbc.update("""
                update feishu_oauth_intents
                set tenant_key=?,feishu_user_id=?,open_id=?,union_id=?,display_name=?,callback_completed_at=now()
                where id=?
                """, identity.tenantKey(), identity.userId(), identity.openId(), identity.unionId(),
                limited(identity.displayName(), 160), intent.id());

        BindingRow binding = findActiveBinding(integration.id(), identity.tenantKey(), identity.userId());
        if (intent.purpose() == Purpose.SUBSCRIBE) {
            if (intent.membershipId() == null) throw ApiException.conflict("FEISHU_BIND_SESSION_LOST", "授权会话已失效");
            CloudPrincipal principal = users.loadByMembershipId(intent.membershipId());
            permissions.requireAdmin(principal.actor());
            if (!principal.actor().organizationId().equals(integration.organizationId())) {
                throw ApiException.forbidden("不能跨组织授权飞书文档事件");
            }
            integrations.markEventSubscriptionAuthorized(integration.id());
            consume(intent.id());
            audit.record(principal.actor(), "FEISHU_EVENT_SUBSCRIPTION_AUTHORIZED", "FEISHU_BITABLE_INTEGRATION",
                    integration.id().toString(), "SUCCESS", Map.of("userTokenPersisted", false));
            return new CallbackResult(principal, intent.returnTo(), null, null);
        }
        if (intent.purpose() == Purpose.BIND) {
            if (intent.membershipId() == null) throw ApiException.conflict("FEISHU_BIND_SESSION_LOST", "绑定会话已失效");
            CloudPrincipal principal = users.loadByMembershipId(intent.membershipId());
            bind(integration, principal, identity);
            consume(intent.id());
            return new CallbackResult(principal, intent.returnTo(), null, null);
        }
        if (binding != null) {
            CloudPrincipal principal = users.loadByMembershipId(binding.membershipId());
            if (principal.passwordChangeRequired()) {
                throw ApiException.conflict("PASSWORD_CHANGE_REQUIRED", "请先使用密码登录并完成密码更新");
            }
            jdbc.update("update feishu_identity_bindings set last_login_at=now(),updated_at=now() where id=?", binding.id());
            consume(intent.id());
            audit.record(principal.actor(), "FEISHU_LOGIN", "FEISHU_IDENTITY_BINDING", binding.id().toString(), "SUCCESS", null);
            return new CallbackResult(principal, intent.returnTo(), null, null);
        }
        String confirmation = randomToken();
        jdbc.update("update feishu_oauth_intents set confirmation_hash=? where id=?", sha256(confirmation), intent.id());
        String confirmationPage = UriComponentsBuilder.fromPath("/login")
                .queryParam("feishuStatus", "confirm")
                .queryParam("feishuIntent", intent.id())
                .queryParam("redirect", intent.returnTo())
                .build().encode().toUriString();
        return new CallbackResult(null, confirmationPage, confirmation, intent.id());
    }

    @Transactional
    public CloudPrincipal confirm(UUID intentId, String confirmationToken, String username, String password) {
        IntentRow intent = jdbc.query("select * from feishu_oauth_intents where id=? for update", intentMapper(), intentId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("FEISHU_INTENT_NOT_FOUND", "飞书绑定请求不存在"));
        if (intent.purpose() != Purpose.LOGIN || intent.callbackCompletedAt() == null || intent.consumedAt() != null
                || intent.expiresAt().isBefore(Instant.now()) || intent.confirmationHash() == null
                || !MessageDigest.isEqual(intent.confirmationHash().getBytes(StandardCharsets.US_ASCII),
                sha256(required(confirmationToken, "FEISHU_CONFIRMATION_REQUIRED", "飞书绑定确认已失效"))
                        .getBytes(StandardCharsets.US_ASCII))) {
            throw ApiException.conflict("FEISHU_BIND_CONFIRMATION_INVALID", "飞书绑定确认已使用或过期");
        }
        final Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        if (!(authentication.getPrincipal() instanceof CloudPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        FeishuIntegrationService.IntegrationRow integration = integrations.findById(intent.integrationId());
        if (integration == null || !principal.actor().organizationId().equals(integration.organizationId())) {
            throw ApiException.forbidden("该英格云账号不属于本次飞书授权的组织");
        }
        FeishuBitableClient.FeishuIdentity identity = new FeishuBitableClient.FeishuIdentity(
                intent.tenantKey(), intent.feishuUserId(), intent.openId(), intent.unionId(), intent.displayName());
        bind(integration, principal, identity);
        consume(intent.id());
        return principal;
    }

    @Transactional(readOnly = true)
    public FeishuAuthDtos.BindingResponse binding() {
        CloudPrincipal principal = requiredPrincipal();
        boolean available = oauthTransportReady()
                && integrations.findOAuthByOrganization(principal.actor().organizationId()) != null;
        BindingRow row = jdbc.query("""
                select binding.* from feishu_identity_bindings binding
                where binding.membership_id=? and binding.organization_id=? and binding.active=true
                """, bindingMapper(), principal.actor().membershipId(), principal.actor().organizationId())
                .stream().findFirst().orElse(null);
        boolean httpsRequired = !redirectUri.startsWith("https://");
        return row == null ? new FeishuAuthDtos.BindingResponse(false, available, httpsRequired,
                null, null, null, null, null, null)
                : new FeishuAuthDtos.BindingResponse(true, available, httpsRequired,
                row.id(), row.tenantKey(), row.feishuUserId(),
                row.displayName(), row.boundAt(), row.lastLoginAt());
    }

    @Transactional(readOnly = true)
    public BindingContext requireActiveBinding() {
        CloudPrincipal principal = requiredPrincipal();
        BindingRow row = jdbc.query("""
                select binding.* from feishu_identity_bindings binding
                join memberships membership on membership.id=binding.membership_id and membership.status='ACTIVE'
                join user_accounts account on account.id=binding.user_account_id and account.enabled=true
                join organizations organization on organization.id=binding.organization_id and organization.status='ACTIVE'
                where binding.membership_id=? and binding.organization_id=? and binding.active=true
                """, bindingMapper(), principal.actor().membershipId(), principal.actor().organizationId())
                .stream().findFirst().orElseThrow(() -> ApiException.forbidden("请先绑定当前飞书账号"));
        return new BindingContext(row.integrationId(), row.organizationId(), row.membershipId(),
                row.tenantKey(), row.feishuUserId(), row.displayName());
    }

    @Transactional
    public void unbind(String currentPassword) {
        CloudPrincipal principal = requiredPrincipal();
        authenticateCurrent(principal, currentPassword);
        int changed = jdbc.update("""
                update feishu_identity_bindings
                set active=false,revoked_at=now(),revoked_by_membership_id=?,updated_at=now(),version=version+1
                where organization_id=? and membership_id=? and active=true
                """, principal.actor().membershipId(), principal.actor().organizationId(), principal.actor().membershipId());
        if (changed == 0) throw ApiException.notFound("FEISHU_BINDING_NOT_FOUND", "当前账号尚未绑定飞书");
        audit.record(principal.actor(), "FEISHU_BINDING_REVOKED", "FEISHU_IDENTITY_BINDING",
                principal.actor().membershipId().toString(), "SUCCESS", Map.of("source", "SELF"));
    }

    @Transactional(readOnly = true)
    public List<FeishuAuthDtos.AdminBindingResponse> adminBindings() {
        CurrentActor actor = requiredPrincipal().actor();
        permissions.requireAdmin(actor);
        return jdbc.query("""
                select binding.*,account.display_name member_display_name
                from feishu_identity_bindings binding
                join user_accounts account on account.id=binding.user_account_id
                where binding.organization_id=? order by binding.created_at desc
                """, (rs, index) -> new FeishuAuthDtos.AdminBindingResponse(
                rs.getObject("id", UUID.class), rs.getObject("membership_id", UUID.class),
                rs.getObject("user_account_id", UUID.class), rs.getString("member_display_name"),
                rs.getString("tenant_key"), rs.getString("feishu_user_id"), rs.getString("display_name"),
                rs.getBoolean("active"), rs.getTimestamp("bound_at").toInstant(),
                instant(rs.getTimestamp("last_login_at")), instant(rs.getTimestamp("revoked_at"))), actor.organizationId());
    }

    @Transactional
    public void adminRevoke(UUID bindingId) {
        CurrentActor actor = requiredPrincipal().actor();
        permissions.requireAdmin(actor);
        int changed = jdbc.update("""
                update feishu_identity_bindings
                set active=false,revoked_at=now(),revoked_by_membership_id=?,updated_at=now(),version=version+1
                where id=? and organization_id=? and active=true
                """, actor.membershipId(), bindingId, actor.organizationId());
        if (changed == 0) throw ApiException.notFound("FEISHU_BINDING_NOT_FOUND", "绑定不存在或已经撤销");
        audit.record(actor, "FEISHU_BINDING_REVOKED", "FEISHU_IDENTITY_BINDING", bindingId.toString(),
                "SUCCESS", Map.of("source", "ADMIN"));
    }

    private void bind(FeishuIntegrationService.IntegrationRow integration, CloudPrincipal principal,
                      FeishuBitableClient.FeishuIdentity identity) {
        if (!principal.actor().organizationId().equals(integration.organizationId())) {
            throw ApiException.forbidden("不能跨组织绑定飞书身份");
        }
        BindingRow identityOwner = findAnyIdentity(identity.tenantKey(), identity.userId());
        if (identityOwner != null && !identityOwner.membershipId().equals(principal.actor().membershipId())) {
            throw ApiException.conflict("FEISHU_IDENTITY_ALREADY_BOUND", "该飞书账号已绑定其他英格云成员");
        }
        BindingRow membershipBinding = findAnyMembership(integration.id(), principal.actor().membershipId());
        if (membershipBinding != null && !membershipBinding.feishuUserId().equals(identity.userId())) {
            throw ApiException.conflict("YINGGE_ACCOUNT_ALREADY_BOUND", "该英格云账号已绑定其他飞书身份，请先解绑");
        }
        UUID id = membershipBinding == null ? UUID.randomUUID() : membershipBinding.id();
        if (membershipBinding == null) {
            try {
                jdbc.update("""
                        insert into feishu_identity_bindings
                        (id,integration_id,organization_id,membership_id,user_account_id,tenant_key,feishu_user_id,
                         open_id,union_id,display_name,active,bound_at)
                        values (?,?,?,?,?,?,?,?,?,?,true,now())
                        """, id, integration.id(), integration.organizationId(), principal.actor().membershipId(),
                        principal.actor().userId(), identity.tenantKey(), identity.userId(), identity.openId(),
                        identity.unionId(), limited(identity.displayName(), 160));
            } catch (DuplicateKeyException exception) {
                throw ApiException.conflict("FEISHU_BINDING_CONFLICT", "飞书或英格云账号已被绑定，请刷新后重试");
            }
        } else {
            jdbc.update("""
                    update feishu_identity_bindings
                    set active=true,open_id=?,union_id=?,display_name=?,bound_at=now(),revoked_at=null,
                        revoked_by_membership_id=null,updated_at=now(),version=version+1 where id=?
                    """, identity.openId(), identity.unionId(), limited(identity.displayName(), 160), id);
        }
        audit.record(principal.actor(), "FEISHU_IDENTITY_BOUND", "FEISHU_IDENTITY_BINDING", id.toString(),
                "SUCCESS", Map.of("tenantBound", true));
    }

    private FeishuIntegrationService.IntegrationRow selectIntegration(Purpose purpose, UUID requested,
                                                                       CloudPrincipal principal) {
        if (purpose == Purpose.SUBSCRIBE) {
            FeishuIntegrationService.IntegrationRow row = integrations.findActiveByOrganization(
                    principal.actor().organizationId());
            if (row == null || requested != null && !requested.equals(row.id())) {
                throw ApiException.notFound("FEISHU_INTEGRATION_NOT_CONFIGURED", "当前组织尚未启用飞书同步");
            }
            return row;
        }
        if (purpose == Purpose.BIND) {
            FeishuIntegrationService.IntegrationRow row = integrations.findOAuthByOrganization(principal.actor().organizationId());
            if (row == null || requested != null && !requested.equals(row.id())) {
                throw ApiException.notFound("FEISHU_LOGIN_NOT_CONFIGURED", "当前组织尚未启用飞书登录");
            }
            return row;
        }
        if (requested != null) {
            FeishuIntegrationService.IntegrationRow row = integrations.findActiveOAuthById(requested);
            if (row == null) throw ApiException.notFound("FEISHU_LOGIN_NOT_CONFIGURED", "飞书登录不可用");
            return row;
        }
        List<FeishuIntegrationService.IntegrationRow> rows = integrations.oauthIntegrations();
        if (rows.isEmpty()) throw ApiException.notFound("FEISHU_LOGIN_NOT_CONFIGURED", "尚未启用飞书登录");
        if (rows.size() > 1) throw ApiException.conflict("FEISHU_ORGANIZATION_REQUIRED", "请选择要登录的英格云组织");
        return rows.getFirst();
    }

    private BindingRow findActiveBinding(UUID integrationId, String tenantKey, String userId) {
        return jdbc.query("""
                select * from feishu_identity_bindings
                where integration_id=? and tenant_key=? and feishu_user_id=? and active=true
                """, bindingMapper(), integrationId, tenantKey, userId).stream().findFirst().orElse(null);
    }

    private BindingRow findAnyIdentity(String tenantKey, String userId) {
        return jdbc.query("""
                select * from feishu_identity_bindings
                where tenant_key=? and feishu_user_id=?
                """, bindingMapper(), tenantKey, userId).stream().findFirst().orElse(null);
    }

    private BindingRow findAnyMembership(UUID integrationId, UUID membershipId) {
        return jdbc.query("select * from feishu_identity_bindings where integration_id=? and membership_id=?",
                bindingMapper(), integrationId, membershipId).stream().findFirst().orElse(null);
    }

    private org.springframework.jdbc.core.RowMapper<BindingRow> bindingMapper() {
        return (rs, index) -> new BindingRow(rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getObject("membership_id", UUID.class),
                rs.getObject("user_account_id", UUID.class), rs.getString("tenant_key"), rs.getString("feishu_user_id"),
                rs.getString("display_name"), rs.getBoolean("active"), rs.getTimestamp("bound_at").toInstant(),
                instant(rs.getTimestamp("last_login_at")), instant(rs.getTimestamp("revoked_at")));
    }

    private org.springframework.jdbc.core.RowMapper<IntentRow> intentMapper() {
        return (rs, index) -> new IntentRow(rs.getObject("id", UUID.class),
                rs.getObject("integration_id", UUID.class), Purpose.valueOf(rs.getString("purpose")),
                rs.getObject("membership_id", UUID.class), rs.getString("confirmation_hash"),
                rs.getString("return_to"), rs.getString("tenant_key"), rs.getString("feishu_user_id"),
                rs.getString("open_id"), rs.getString("union_id"), rs.getString("display_name"),
                rs.getTimestamp("expires_at").toInstant(), instant(rs.getTimestamp("callback_completed_at")),
                instant(rs.getTimestamp("consumed_at")));
    }

    private void consume(UUID intentId) {
        int changed = jdbc.update("update feishu_oauth_intents set consumed_at=now(),confirmation_hash=null where id=? and consumed_at is null", intentId);
        if (changed == 0) throw ApiException.conflict("FEISHU_OAUTH_REPLAYED", "该飞书授权已经使用");
    }

    private void authenticateCurrent(CloudPrincipal principal, String password) {
        try {
            authenticationManager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(principal.getUsername(), password));
        } catch (RuntimeException exception) {
            throw ApiException.badRequest("CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
    }

    private void requireOAuthTransport() {
        if (redirectUri.isBlank()) {
            throw ApiException.conflict("FEISHU_REDIRECT_NOT_CONFIGURED", "尚未配置飞书 OAuth 回调地址");
        }
        if (!oauthTransportReady()) {
            throw ApiException.conflict("FEISHU_HTTPS_REQUIRED", "正式飞书登录必须使用可信 HTTPS 地址");
        }
    }

    private boolean oauthTransportReady() {
        if (redirectUri.isBlank()) return false;
        if (redirectUri.startsWith("https://")) return true;
        return allowInsecurePilot
                && redirectUri.matches("http://(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.).+");
    }

    private CloudPrincipal optionalPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof CloudPrincipal principal ? principal : null;
    }

    private CloudPrincipal requiredPrincipal() {
        CloudPrincipal principal = optionalPrincipal();
        if (principal == null) throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        return principal;
    }

    private String safeReturnTo(String value, Purpose purpose) {
        String fallback = switch (purpose) {
            case BIND -> "/settings?feishu=bound";
            case SUBSCRIBE -> "/admin/feishu?feishuSubscription=ready";
            case LOGIN -> "/dashboard";
        };
        return safeReturnPath(value, fallback);
    }

    static String safeReturnPath(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String clean = value.trim();
        if (!clean.matches("/[A-Za-z0-9/_?=&.%-]{0,280}") || clean.startsWith("//")) return fallback;
        try {
            String decoded = java.net.URLDecoder.decode(clean, StandardCharsets.UTF_8);
            if (!decoded.startsWith("/") || decoded.startsWith("//") || decoded.contains("\\")
                    || decoded.matches("(?i)^/[a-z][a-z0-9+.-]*:.*")
                    || decoded.matches("(?i)^/api(?:/|$).*")
                    || decoded.matches("(?i)^/feishu/crm(?:[/?#]|$).*$")) {
                return fallback;
            }
            return clean;
        } catch (IllegalArgumentException invalidEncoding) {
            return fallback;
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String required(String value, String code, String message) {
        if (value == null || value.isBlank()) throw ApiException.badRequest(code, message);
        return value.trim();
    }

    private String limited(String value, int max) {
        if (value == null) return null;
        String clean = value.trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    public record StartResult(String authorizeUrl, UUID intentId) {}
    public record CallbackResult(CloudPrincipal principal, String redirectTo, String confirmationToken, UUID intentId) {}
    public record BindingContext(UUID integrationId, UUID organizationId, UUID membershipId,
                                 String tenantKey, String feishuUserId, String feishuDisplayName) {}
    private enum Purpose { LOGIN, BIND, SUBSCRIBE;
        boolean requiresSession() { return this == BIND || this == SUBSCRIBE; }
        static Purpose parse(String value) {
            if (value == null || value.isBlank()) return LOGIN;
            try { return valueOf(value.trim().toUpperCase()); }
            catch (IllegalArgumentException exception) { throw ApiException.badRequest("FEISHU_PURPOSE_INVALID", "飞书授权用途无效"); }
        }
    }
    private record BindingRow(UUID id, UUID integrationId, UUID organizationId, UUID membershipId, UUID userId,
                              String tenantKey, String feishuUserId, String displayName, boolean active,
                              Instant boundAt, Instant lastLoginAt, Instant revokedAt) {}
    private record IntentRow(UUID id, UUID integrationId, Purpose purpose, UUID membershipId,
                             String confirmationHash, String returnTo, String tenantKey, String feishuUserId,
                             String openId, String unionId, String displayName, Instant expiresAt,
                             Instant callbackCompletedAt, Instant consumedAt) {}
}
