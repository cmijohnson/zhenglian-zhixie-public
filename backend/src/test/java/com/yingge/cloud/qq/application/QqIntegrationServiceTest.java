package com.yingge.cloud.qq.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.qq.infrastructure.QqBridgeClient;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QqIntegrationServiceTest {
    @Test
    void rejectsRangesThatHaveMoreMessagesAfterTheConfiguredLimit() {
        assertThatThrownBy(() -> QqIntegrationService.requireCompleteImport(true, 10_000, 10_000))
                .hasMessageContaining("单次最多导入 10000 条消息");
    }

    @Test
    void acceptsACompleteRangeAtTheConfiguredLimit() {
        assertThatCode(() -> QqIntegrationService.requireCompleteImport(false, 10_000, 10_000))
                .doesNotThrowAnyException();
    }

    @Test
    void recognizesSpringAndPostgresUniqueViolations() {
        assertThat(QqIntegrationService.isUniqueViolation(new DuplicateKeyException("duplicate"))).isTrue();
        assertThat(QqIntegrationService.isUniqueViolation(
                new IllegalStateException(new SQLException("duplicate", "23505")))).isTrue();
        assertThat(QqIntegrationService.isUniqueViolation(new SQLException("other", "22000"))).isFalse();
    }

    @Test
    void recreatesAMissingQceInstanceWithTheSameMemberScopedKey() throws Exception {
        QqBridgeClient bridge = mock(QqBridgeClient.class);
        ObjectMapper mapper = new ObjectMapper();
        String instanceId = "qce-uat-instance";
        String instanceKey = "organization:member";
        when(bridge.status(instanceId))
                .thenThrow(ApiException.unavailable("QQ_BRIDGE_UNAVAILABLE", "missing"))
                .thenReturn(mapper.readTree("{\"status\":\"PAIRING\",\"qrCodeDataUrl\":\"data:image/png;base64,test\"}"));
        when(bridge.create(instanceKey)).thenReturn(mapper.readTree("{\"instanceId\":\"qce-uat-instance\"}"));

        var status = QqIntegrationService.bridgeStatusWithRecovery(bridge, instanceId, instanceKey);

        assertThat(status.path("status").asText()).isEqualTo("PAIRING");
        var order = inOrder(bridge);
        order.verify(bridge).status(instanceId);
        order.verify(bridge).create(instanceKey);
        order.verify(bridge).status(instanceId);
    }

    @Test
    void refusesToBindARecoveredContainerToADifferentInstance() throws Exception {
        QqBridgeClient bridge = mock(QqBridgeClient.class);
        when(bridge.status("expected"))
                .thenThrow(ApiException.unavailable("QQ_BRIDGE_UNAVAILABLE", "missing"));
        when(bridge.create("organization:member"))
                .thenReturn(new ObjectMapper().readTree("{\"instanceId\":\"unexpected\"}"));

        assertThatThrownBy(() -> QqIntegrationService.bridgeStatusWithRecovery(
                bridge, "expected", "organization:member"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("QQ 实例恢复校验失败");
    }
}
