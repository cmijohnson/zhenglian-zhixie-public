package com.yingge.cloud.report.api;

import com.yingge.cloud.report.application.DailyReportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/daily-reports")
public class DailyReportController {
    private final DailyReportService reports;

    public DailyReportController(DailyReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/today")
    public DailyReportDtos.Response today() {
        return reports.today();
    }

    @GetMapping
    public List<DailyReportDtos.Response> history() {
        return reports.history();
    }

    @GetMapping("/calendar")
    public DailyReportDtos.CalendarResponse calendar(@org.springframework.web.bind.annotation.RequestParam String month) {
        return reports.calendar(month);
    }

    @PutMapping("/{date}")
    public DailyReportDtos.Response submit(@PathVariable LocalDate date,
                                           @Valid @RequestBody DailyReportDtos.UpsertRequest body) {
        return reports.submit(date, body);
    }
}
