package com.yingge.cloud.market.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MarketDeadlineClockSkewTest {
    private final MarketDeadlineService service = new MarketDeadlineService(
            mock(NamedParameterJdbcTemplate.class), new ObjectMapper(), mock(CurrentActorProvider.class),
            mock(PermissionService.class), mock(CustomerService.class), mock(AuditService.class));

    @Test
    void nullAndSmallFutureClockSkewUseServerReceiveTime() {
        Instant received = Instant.parse("2026-08-27T01:00:00Z");

        assertThat(service.normalizeOccurredAt(null, received)).isEqualTo(received);
        assertThat(service.normalizeOccurredAt(received.plusSeconds(119), received)).isEqualTo(received);
        assertThat(service.normalizeOccurredAt(received.minusSeconds(1), received))
                .isEqualTo(received.minusSeconds(1));
    }

    @Test
    void clockSkewBeyondTwoMinutesRemainsInvalid() {
        Instant received = Instant.parse("2026-08-27T01:00:00Z");

        assertThatThrownBy(() -> service.normalizeOccurredAt(received.plusSeconds(121), received))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("INTERACTION_TIME_IN_FUTURE"));
    }
}
