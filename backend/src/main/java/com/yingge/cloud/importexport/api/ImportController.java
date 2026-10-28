package com.yingge.cloud.importexport.api;

import com.yingge.cloud.importexport.application.ImportExportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {
    private final ImportExportService service;

    public ImportController(ImportExportService service) {
        this.service = service;
    }

    @PostMapping("/customers/preview")
    public ImportExportService.ImportPreview preview(@RequestPart("file") MultipartFile file) {
        return service.preview(file);
    }

    @PostMapping("/{jobId}/commit")
    public ImportExportService.ImportReport commit(@PathVariable UUID jobId) {
        return service.commit(jobId);
    }

    @GetMapping("/{jobId}/report")
    public ImportExportService.ImportReport report(@PathVariable UUID jobId) {
        return service.report(jobId);
    }
}

