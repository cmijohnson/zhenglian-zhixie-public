package com.yingge.cloud.importexport.api;

import com.yingge.cloud.importexport.application.ExportService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/exports")
public class ExportController {
    private final ExportService service;

    public ExportController(ExportService service) {
        this.service = service;
    }

    @GetMapping("/customers")
    public void customers(@RequestParam(defaultValue = "xlsx") String format, HttpServletResponse response) throws IOException {
        service.requireCustomerExportAccess();
        configure(response, "客户档案-" + LocalDate.now(), format);
        service.exportCustomers(format, response.getOutputStream());
    }

    @GetMapping("/customers/{customerId}/timeline")
    public void timeline(@PathVariable UUID customerId, @RequestParam(defaultValue = "xlsx") String format,
                         HttpServletResponse response) throws IOException {
        service.requireTimelineExportAccess(customerId);
        configure(response, "客户时间线-" + customerId, format);
        service.exportTimeline(customerId, format, response.getOutputStream());
    }

    private void configure(HttpServletResponse response, String basename, String format) {
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        response.setContentType(xlsx ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "text/csv;charset=UTF-8");
        String filename = URLEncoder.encode(basename + (xlsx ? ".xlsx" : ".csv"), StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
    }
}
