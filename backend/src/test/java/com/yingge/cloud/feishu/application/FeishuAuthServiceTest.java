package com.yingge.cloud.feishu.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.infrastructure.CloudUserDetailsService;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.UUID;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuAuthServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FeishuIntegrationService integrations = mock(FeishuIntegrationService.class);
    private final FeishuBitableClient client = mock(FeishuBitableClient.class);
    private final CloudUserDetailsService users = mock(CloudUserDetailsService.class);
    private final AuthenticationManager authentication = mock(AuthenticationManager.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final AuditService audit = mock(AuditService.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void startsLoginOnlyOnConfiguredHttpsCallbackAndUsesSafeReturnPath() {
        UUID integrationId = UUID.randomUUID();
        when(integrations.findActiveOAuthById(integrationId)).thenReturn(integration(integrationId));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        FeishuAuthService service = service("https://cloud.example.cn/api/v1/auth/feishu/callback", false);

        FeishuAuthService.StartResult result = service.start("LOGIN", integrationId,
                "https://attacker.invalid/steal");

        assertThat(result.authorizeUrl())
                .startsWith("https://accounts.feishu.cn/open-apis/authen/v1/authorize?")
                .contains("app_id=cli_test")
                .contains("redirect_uri=https://cloud.example.cn/api/v1/auth/feishu/callback")
                .contains("state=")
                .doesNotContain("docs:event:subscribe");
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void refusesPlainHttpUnlessExplicitPrivateNetworkPilotIsEnabled() {
        UUID integrationId = UUID.randomUUID();
        when(integrations.findActiveOAuthById(integrationId)).thenReturn(integration(integrationId));

        assertThatThrownBy(() -> service(
                "http://127.0.0.1/api/v1/auth/feishu/callback", false)
                .start("LOGIN", integrationId, "/dashboard"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEISHU_HTTPS_REQUIRED"));
    }

    @Test
    void permitsExplicitPrivateNetworkPilotButNeverPublicPlainHttp() {
        UUID integrationId = UUID.randomUUID();
        when(integrations.findActiveOAuthById(integrationId)).thenReturn(integration(integrationId));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(service("http://127.0.0.1/api/v1/auth/feishu/callback", true)
                .start("LOGIN", integrationId, "/dashboard").authorizeUrl()).contains("accounts.feishu.cn");
        assertThatThrownBy(() -> service(
                "http://public.example.cn/api/v1/auth/feishu/callback", true)
                .start("LOGIN", integrationId, "/dashboard"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEISHU_HTTPS_REQUIRED"));
    }

    @Test
    void startsOneTimeOwnerSubscriptionForAnAuthenticatedOrganizationAdmin() {
        UUID integrationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        FeishuIntegrationService.IntegrationRow integration = integration(integrationId, organizationId);
        when(integrations.findActiveByOrganization(organizationId)).thenReturn(integration);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        CurrentActor actor = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), organizationId,
                "admin", "管理员", Set.of(RoleCode.ORG_ADMIN), Set.of(), Set.of());
        com.yingge.cloud.auth.infrastructure.CloudPrincipal principal = mock(
                com.yingge.cloud.auth.infrastructure.CloudPrincipal.class);
        when(principal.actor()).thenReturn(actor);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, "", Set.of()));

        FeishuAuthService.StartResult result = service(
                "http://127.0.0.1/api/v1/auth/feishu/callback", true)
                .start("SUBSCRIBE", integrationId, "/admin/feishu?feishuSubscription=ready");

        assertThat(result.authorizeUrl())
                .contains("accounts.feishu.cn")
                .contains("state=")
                .contains("scope=docs:event:subscribe");
        verify(permissions).requireAdmin(actor);
    }

    @Test
    void rejectsCrossSiteEncodedAndApiReturnPaths() {
        assertThat(FeishuAuthService.safeReturnPath("/customers/abc?tab=timeline", "/dashboard"))
                .isEqualTo("/customers/abc?tab=timeline");
        assertThat(FeishuAuthService.safeReturnPath("//attacker.invalid", "/dashboard"))
                .isEqualTo("/dashboard");
        assertThat(FeishuAuthService.safeReturnPath("/%2Fattacker.invalid", "/dashboard"))
                .isEqualTo("/dashboard");
        assertThat(FeishuAuthService.safeReturnPath("/api/v1/auth/me", "/dashboard"))
                .isEqualTo("/dashboard");
        assertThat(FeishuAuthService.safeReturnPath("/feishu/crm?returnTo=/dashboard", "/dashboard"))
                .isEqualTo("/dashboard");
    }

    private FeishuAuthService service(String callback, boolean pilot) {
        return new FeishuAuthService(jdbc, integrations, client, users, authentication,
                permissions, audit, callback, pilot);
    }

    private FeishuIntegrationService.IntegrationRow integration(UUID id) {
        return integration(id, UUID.randomUUID());
    }

    private FeishuIntegrationService.IntegrationRow integration(UUID id, UUID organizationId) {
        Instant now = Instant.parse("2026-09-03T00:00:00Z");
        return new FeishuIntegrationService.IntegrationRow(id, organizationId, "cli_test", new byte[]{1},
                "base_token", "table_id", "英格云客户档案", true, true, true,
                "READY", "tenant_key", "CONNECTED", now, null, now, now, now, now, null,
                1L, now, now);
    }
}
