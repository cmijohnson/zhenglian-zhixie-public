package com.yingge.cloud.intelligence.api;

import com.yingge.cloud.intelligence.application.CustomerIntelligenceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/customer-intelligence")
public class AdminCustomerIntelligenceController {
    private final CustomerIntelligenceService service;

    public AdminCustomerIntelligenceController(CustomerIntelligenceService service) {
        this.service = service;
    }

    @GetMapping("/settings")
    public CustomerIntelligenceDtos.Settings settings() {
        return service.adminSettings();
    }

    @GetMapping("/runtime")
    public CustomerIntelligenceDtos.RuntimeStatus runtime() {
        return service.runtimeStatus();
    }

    @PutMapping("/settings")
    public CustomerIntelligenceDtos.Settings update(
            @Valid @RequestBody CustomerIntelligenceDtos.UpdateSettingsRequest request) {
        return service.updateSettings(request);
    }

    @PostMapping("/backfill/resume")
    public CustomerIntelligenceDtos.Settings resumeBackfill() {
        return service.setBackfillPaused(false);
    }

    @PostMapping("/backfill/pause")
    public CustomerIntelligenceDtos.Settings pauseBackfill() {
        return service.setBackfillPaused(true);
    }
}
