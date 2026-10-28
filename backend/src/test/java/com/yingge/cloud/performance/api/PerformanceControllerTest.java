package com.yingge.cloud.performance.api;

import com.yingge.cloud.performance.application.PerformanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceControllerTest {
    @Mock
    private PerformanceService performance;

    @InjectMocks
    private PerformanceController controller;

    @Test
    void returnsNoContentForAConfiguredNonScoringDay() {
        LocalDate date = LocalDate.of(2026, 8, 22);
        when(performance.findDay(date)).thenReturn(Optional.empty());

        var response = controller.day(date);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
    }
}
