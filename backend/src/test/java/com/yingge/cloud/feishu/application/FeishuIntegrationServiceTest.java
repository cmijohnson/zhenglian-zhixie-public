package com.yingge.cloud.feishu.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import com.yingge.cloud.feishu.infrastructure.FeishuSecretCipher;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeishuIntegrationServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final FeishuIntegrationService service = new FeishuIntegrationService(
            jdbc, actors, new PermissionService(), mock(AuditService.class),
            mock(FeishuSecretCipher.class), mock(FeishuBitableClient.class));

    @Test
    void configurationNeverSerializesEncryptedOrPlaintextSecret() throws Exception {
        CurrentActor actor = actor(RoleCode.ORG_ADMIN);
        when(actors.require()).thenReturn(actor);
        byte[] encrypted = new byte[]{1, 2, 3, 4};
        FeishuIntegrationService.IntegrationRow row = new FeishuIntegrationService.IntegrationRow(
                UUID.randomUUID(), actor.organizationId(), "cli_example", encrypted, "bascn_example",
                "tbl_example", "英格云客户档案", true, "READY", Instant.now(), Instant.now(),
                Instant.now(), null, 3, Instant.now(), Instant.now());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(actor.organizationId())))
                .thenReturn(List.of(row));
        when(jdbc.queryForMap(anyString(), eq(row.id())))
                .thenReturn(Map.of("pending_count", 3L, "failed_count", 1L));

        var response = service.configuration();
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertThat(response.secretConfigured()).isTrue();
        assertThat(response.pendingCount()).isEqualTo(3);
        assertThat(response.permanentFailureCount()).isEqualTo(1);
        assertThat(serialized).doesNotContain("encryptedSecret", "appSecret", "AQIDBA==");
    }

    @Test
    void nonAdministratorCannotReadControlPlaneConfiguration() {
        when(actors.require()).thenReturn(actor(RoleCode.MEMBER));

        assertThatThrownBy(service::configuration)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("组织管理员");
    }

    @Test
    void standardSchemaHasUniqueKeysAndAllRequiredCustomerFields() {
        assertThat(FeishuCustomerSchema.FIELDS).hasSize(34);
        assertThat(FeishuCustomerSchema.FIELDS.stream().map(FeishuBitableClient.FieldDefinition::key))
                .doesNotHaveDuplicates()
                .contains("customerId", "phone", "wechatId", "qqId", "quality", "ownerName",
                        "collaborators", "privateCustomer", "archived", "sourceVersion", "syncedAt");
    }

    @Test
    void safeErrorPreservesStructuredApiErrorCode() {
        assertThat(service.safeError(ApiException.unavailable(
                "FEISHU_SECRET_DECRYPTION_FAILED", "飞书应用密钥无法解密")))
                .isEqualTo("FEISHU_SECRET_DECRYPTION_FAILED");
    }

    private CurrentActor actor(RoleCode role) {
        return new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "tester",
                "测试成员", Set.of(role), Set.of(), Set.of());
    }
}
