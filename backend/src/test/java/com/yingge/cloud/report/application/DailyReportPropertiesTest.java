package com.yingge.cloud.report.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DailyReportPropertiesTest {
    @Test
    void defaultsToLongRunningReportStartWithoutAnEndDate() {
        assertThat(new DailyReportProperties(null).startDate()).isEqualTo(LocalDate.of(2026, 7, 15));
    }
}
