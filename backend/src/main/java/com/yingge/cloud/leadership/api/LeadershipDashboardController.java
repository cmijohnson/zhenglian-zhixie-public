package com.yingge.cloud.leadership.api;

import com.yingge.cloud.leadership.application.LeadershipDashboardService;
import com.yingge.cloud.leadership.application.LeadershipDashboardExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/leadership-dashboard")
public class LeadershipDashboardController {
    private final LeadershipDashboardService dashboards;
    private final LeadershipDashboardExportService exports;

    public LeadershipDashboardController(
            LeadershipDashboardService dashboards,
            LeadershipDashboardExportService exports) {
        this.dashboards = dashboards;
        this.exports = exports;
    }

    @GetMapping
    public LeadershipDashboardDtos.Overview overview(
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) UUID membershipId,
            @RequestParam(required = false) Integer days) {
        return dashboards.overview(teamId, membershipId, days);
    }

    @GetMapping("/export.xlsx")
    public void export(
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) UUID membershipId,
            @RequestParam(required = false) Integer days,
            HttpServletResponse response) throws IOException {
        var workbook = new ByteArrayOutputStream();
        exports.export(teamId, membershipId, days, workbook);
        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String filename = URLEncoder.encode(
                "英格客户云-领导数据分析-" + LocalDate.now() + ".xlsx",
                StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        response.setContentLength(workbook.size());
        workbook.writeTo(response.getOutputStream());
    }
}
