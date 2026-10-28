package com.yingge.cloud.report.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

@ConfigurationProperties("app.daily-report")
public record DailyReportProperties(LocalDate startDate) {
    public DailyReportProperties {
        if (startDate == null) startDate = LocalDate.of(2026, 7, 15);
    }
}
