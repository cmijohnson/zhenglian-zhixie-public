package com.yingge.cloud.intelligence.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerIntelligenceRuntimeMonitorTest {
    @Test
    void linuxMemoryUsesMemAvailableInsteadOfRawFreeMemory() {
        var memory = CustomerIntelligenceRuntimeMonitor.parseLinuxMemory(List.of(
                "MemTotal:        8000000 kB",
                "MemFree:          400000 kB",
                "MemAvailable:    2800000 kB",
                "Cached:          2400000 kB"));

        assertThat(memory.max()).isEqualTo(8_192_000_000L);
        assertThat(memory.used()).isEqualTo(5_324_800_000L);
        assertThat(memory.percent()).isEqualTo(65.0d);
    }

    @Test
    void incompleteLinuxMemoryFallsBackCleanly() {
        var memory = CustomerIntelligenceRuntimeMonitor.parseLinuxMemory(List.of(
                "MemTotal:        8000000 kB", "MemFree:          400000 kB"));

        assertThat(memory.max()).isNull();
        assertThat(memory.used()).isNull();
        assertThat(memory.percent()).isNull();
    }

    @Test
    void reportsNormalForAnIdleInstance() {
        var pressure = CustomerIntelligenceRuntimeMonitor.evaluatePressure(
                new CustomerIntelligenceRuntimeMonitor.PressureInputs(
                        18d, 35d, 28d, 20d, 0, 2, 0, 20L));

        assertThat(pressure.level()).isEqualTo("NORMAL");
    }

    @Test
    void reportsBusyWhenTheAnalysisQueueWaitsTooLong() {
        var pressure = CustomerIntelligenceRuntimeMonitor.evaluatePressure(
                new CustomerIntelligenceRuntimeMonitor.PressureInputs(
                        30d, 50d, 40d, 30d, 0, 4, 0, 360L));

        assertThat(pressure.level()).isEqualTo("BUSY");
    }

    @Test
    void reportsHighWhenConnectionsAreWaiting() {
        var pressure = CustomerIntelligenceRuntimeMonitor.evaluatePressure(
                new CustomerIntelligenceRuntimeMonitor.PressureInputs(
                        40d, 60d, 50d, 65d, 2, 8, 0, 60L));

        assertThat(pressure.level()).isEqualTo("HIGH");
    }
}
