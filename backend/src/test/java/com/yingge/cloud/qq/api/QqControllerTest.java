package com.yingge.cloud.qq.api;

import com.yingge.cloud.qq.application.QqIntegrationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QqControllerTest {
    @Mock
    private QqIntegrationService service;

    @InjectMocks
    private QqController controller;

    @Test
    void returnsNoContentWhenTheMemberHasNoQqConnection() {
        when(service.myConnection()).thenReturn(null);

        var response = controller.mine();

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void returnsTheExistingConnectionAsJsonBody() {
        var connection = new QqDtos.Connection(
                UUID.randomUUID(), "CONNECTED", "12***89", null, Instant.now(), Instant.now());
        when(service.myConnection()).thenReturn(connection);

        var response = controller.mine();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(connection);
    }
}
