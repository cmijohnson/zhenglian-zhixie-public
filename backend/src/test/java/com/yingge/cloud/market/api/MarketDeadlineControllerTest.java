package com.yingge.cloud.market.api;

import com.yingge.cloud.market.application.MarketDeadlineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketDeadlineControllerTest {
    @Mock
    private MarketDeadlineService service;

    @InjectMocks
    private MarketDeadlineController controller;

    @Test
    void returnsNoContentWhenVisibleCustomerHasNoDeadlineSnapshot() {
        UUID customerId = UUID.randomUUID();
        when(service.findSnapshot(customerId)).thenReturn(Optional.empty());

        var response = controller.snapshot(customerId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }
}
