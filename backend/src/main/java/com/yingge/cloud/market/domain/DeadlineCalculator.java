package com.yingge.cloud.market.domain;

import com.yingge.cloud.market.api.MarketDtos;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;

public final class DeadlineCalculator {
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private DeadlineCalculator() {}

    public static Instant calculate(Instant base, CustomerClassLevel level,
                                    List<MarketDtos.DateRange> peakPeriods,
                                    List<MarketDtos.DateRange> pausePeriods,
                                    int aPeakHours, int bPeakHours,
                                    int aOffPeakHours, int bOffPeakHours) {
        return calculate(base, level, peakPeriods, pausePeriods, aPeakHours, bPeakHours,
                aOffPeakHours, bOffPeakHours, true);
    }

    public static Instant calculate(Instant base, CustomerClassLevel level,
                                    List<MarketDtos.DateRange> peakPeriods,
                                    List<MarketDtos.DateRange> pausePeriods,
                                    int aPeakHours, int bPeakHours,
                                    int aOffPeakHours, int bOffPeakHours,
                                    boolean weekendsCount) {
        LocalDate businessDate = base.atZone(BUSINESS_ZONE).toLocalDate();
        boolean peak = peakPeriods.stream().anyMatch(period -> contains(period, businessDate));
        int hours = switch (level) {
            case A -> peak ? aPeakHours : aOffPeakHours;
            case B -> peak ? bPeakHours : bOffPeakHours;
            case C -> throw new IllegalArgumentException("C 类没有 A/B 归属期限");
        };
        if (!weekendsCount) {
            return addCountedHours(base, hours, pausePeriods);
        }
        Instant deadline = base.plus(Duration.ofHours(hours));
        List<MarketDtos.DateRange> sorted = pausePeriods.stream()
                .sorted(Comparator.comparing(MarketDtos.DateRange::start))
                .toList();
        for (MarketDtos.DateRange pause : sorted) {
            Instant pauseStart = pause.start().atStartOfDay(BUSINESS_ZONE).toInstant();
            Instant pauseEnd = pause.end().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
            if (!pauseEnd.isAfter(base) || !pauseStart.isBefore(deadline)) continue;
            Instant overlapStart = pauseStart.isAfter(base) ? pauseStart : base;
            Instant overlapEnd = pauseEnd.isBefore(deadline) ? pauseEnd : deadline;
            if (overlapEnd.isAfter(overlapStart)) {
                deadline = deadline.plus(Duration.between(overlapStart, overlapEnd));
            }
        }
        return deadline;
    }

    private static Instant addCountedHours(Instant base, int hours,
                                           List<MarketDtos.DateRange> pausePeriods) {
        long remainingSeconds = Duration.ofHours(hours).toSeconds();
        Instant cursor = base;
        while (remainingSeconds > 0) {
            ZonedDateTime local = cursor.atZone(BUSINESS_ZONE);
            LocalDate date = local.toLocalDate();
            Instant nextMidnight = date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
            long untilMidnight = Duration.between(cursor, nextMidnight).toSeconds();
            boolean paused = local.getDayOfWeek().getValue() >= 6
                    || pausePeriods.stream().anyMatch(period -> contains(period, date));
            if (paused) {
                cursor = nextMidnight;
                continue;
            }
            long counted = Math.min(remainingSeconds, untilMidnight);
            cursor = cursor.plusSeconds(counted);
            remainingSeconds -= counted;
        }
        return cursor;
    }

    public static void requireValidRanges(List<MarketDtos.DateRange> ranges, String label) {
        List<MarketDtos.DateRange> sorted = ranges.stream()
                .sorted(Comparator.comparing(MarketDtos.DateRange::start))
                .toList();
        MarketDtos.DateRange previous = null;
        for (MarketDtos.DateRange current : sorted) {
            if (current.end().isBefore(current.start())) {
                throw new IllegalArgumentException(label + "结束日期不能早于开始日期");
            }
            if (previous != null && !current.start().isAfter(previous.end())) {
                throw new IllegalArgumentException(label + "区间不能重叠");
            }
            previous = current;
        }
    }

    private static boolean contains(MarketDtos.DateRange period, LocalDate date) {
        return !date.isBefore(period.start()) && !date.isAfter(period.end());
    }
}
