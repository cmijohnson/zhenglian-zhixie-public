package com.yingge.cloud.market.domain;

import com.yingge.cloud.market.api.MarketDtos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeadlineCalculatorTest {
    @Test
    void appliesPeakDurationsByCustomerLevel() {
        Instant base = Instant.parse("2026-07-20T01:00:00Z");
        List<MarketDtos.DateRange> peak = List.of(new MarketDtos.DateRange(
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31)));

        assertThat(DeadlineCalculator.calculate(base, CustomerClassLevel.A, peak, List.of(),
                72, 96, 168, 216)).isEqualTo(Instant.parse("2026-07-23T01:00:00Z"));
        assertThat(DeadlineCalculator.calculate(base, CustomerClassLevel.B, peak, List.of(),
                72, 96, 168, 216)).isEqualTo(Instant.parse("2026-07-24T01:00:00Z"));
    }

    @Test
    void shiftsDeadlineByPauseOverlapIncludingNewlyReachedPause() {
        Instant base = Instant.parse("2026-01-01T04:00:00Z");
        List<MarketDtos.DateRange> pauses = List.of(
                new MarketDtos.DateRange(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2)),
                new MarketDtos.DateRange(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5)));

        assertThat(DeadlineCalculator.calculate(base, CustomerClassLevel.A, List.of(), pauses,
                72, 96, 168, 216)).isEqualTo(Instant.parse("2026-01-10T04:00:00Z"));
    }

    @Test
    void rejectsOverlappingOrReversedCalendarRanges() {
        assertThatThrownBy(() -> DeadlineCalculator.requireValidRanges(List.of(
                new MarketDtos.DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 5)),
                new MarketDtos.DateRange(LocalDate.of(2026, 7, 5), LocalDate.of(2026, 7, 8))), "暂停"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能重叠");
        assertThatThrownBy(() -> DeadlineCalculator.requireValidRanges(List.of(
                new MarketDtos.DateRange(LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 1))), "旺季"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能早于");
    }

    @Test
    void excludesWeekendsWhenConfigured() {
        Instant fridayMorning = Instant.parse("2026-07-17T01:00:00Z");

        assertThat(DeadlineCalculator.calculate(fridayMorning, CustomerClassLevel.A,
                List.of(), List.of(), 72, 96, 24, 216, false))
                .isEqualTo(Instant.parse("2026-07-20T01:00:00Z"));
    }
}
