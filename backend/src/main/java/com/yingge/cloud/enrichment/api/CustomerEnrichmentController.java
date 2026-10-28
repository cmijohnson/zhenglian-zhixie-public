package com.yingge.cloud.enrichment.api;

import com.yingge.cloud.enrichment.application.CustomerEnrichmentService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/customer-enrichment-jobs")
public class CustomerEnrichmentController {
    private final CustomerEnrichmentService service;

    public CustomerEnrichmentController(CustomerEnrichmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<CustomerEnrichmentService.JobSummary> jobs() { return service.jobs(); }

    @PostMapping("/preview")
    public CustomerEnrichmentService.Preview preview(@RequestPart("file") MultipartFile file) {
        return service.preview(file);
    }

    @GetMapping("/{id}")
    public CustomerEnrichmentService.Preview report(@PathVariable UUID id) { return service.report(id); }

    @PostMapping("/{id}/commit")
    public CustomerEnrichmentService.Preview commit(@PathVariable UUID id) { return service.commit(id); }

    @GetMapping("/{id}/report.csv")
    public void download(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        List<CustomerEnrichmentService.RowResult> rows = service.allRows(id);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=customer-enrichment-" + id + ".csv");
        var writer = response.getWriter();
        writer.write('\uFEFF');
        writer.println("行号,姓名,专业,年级,高中,生源地,映射状态,处理状态,说明");
        for (var row : rows) {
            writer.println(csv(row.rowNumber()) + ',' + csv(row.fullName()) + ',' + csv(row.major()) + ','
                    + csv(row.grade()) + ',' + csv(row.highSchool()) + ',' + csv(row.sourceLocation()) + ','
                    + csv(row.mappingStatus()) + ',' + csv(row.status()) + ',' + csv(row.message()));
        }
    }

    private String csv(Object value) {
        String text = value == null ? "" : value.toString();
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
