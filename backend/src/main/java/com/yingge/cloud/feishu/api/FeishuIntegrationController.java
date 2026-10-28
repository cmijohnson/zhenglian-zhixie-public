package com.yingge.cloud.feishu.api;

import com.yingge.cloud.feishu.application.FeishuIntegrationService;
import com.yingge.cloud.feishu.application.FeishuInboundAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/feishu-integration")
public class FeishuIntegrationController {
    private final FeishuIntegrationService service;
    private final FeishuInboundAdminService inbound;

    public FeishuIntegrationController(FeishuIntegrationService service, FeishuInboundAdminService inbound) {
        this.service = service;
        this.inbound = inbound;
    }

    @GetMapping
    public ResponseEntity<FeishuIntegrationDtos.ConfigurationResponse> configuration() {
        FeishuIntegrationDtos.ConfigurationResponse configuration = service.configuration();
        return configuration == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(configuration);
    }

    @PutMapping
    public FeishuIntegrationDtos.ConfigurationResponse save(
            @Valid @RequestBody FeishuIntegrationDtos.ConfigurationRequest body) {
        return service.save(body);
    }

    @PostMapping("/test")
    public FeishuIntegrationDtos.ConnectionTestResponse test() {
        return service.testConnection();
    }

    @PostMapping("/provision")
    public FeishuIntegrationDtos.ProvisionResponse provision() {
        return service.provision();
    }

    @GetMapping("/schema")
    public FeishuIntegrationDtos.SchemaResponse schema() {
        return service.schema();
    }

    @PostMapping("/initial-sync/preview")
    public FeishuIntegrationDtos.InitialSyncPreview preview() {
        return service.initialPreview();
    }

    @PostMapping("/initial-sync/start")
    public FeishuIntegrationDtos.RunResponse startInitialSync() {
        return service.startInitialSync();
    }

    @PostMapping("/sync-now")
    public FeishuIntegrationDtos.RunResponse syncNow() {
        return service.syncNow();
    }

    @PostMapping("/reconcile")
    public FeishuIntegrationDtos.RunResponse reconcile() {
        return service.reconcile();
    }

    @GetMapping("/runs")
    public List<FeishuIntegrationDtos.RunResponse> runs(@RequestParam(defaultValue = "50") int size) {
        return service.runs(size);
    }

    @GetMapping("/failures")
    public List<FeishuIntegrationDtos.FailureResponse> failures(@RequestParam(defaultValue = "100") int size) {
        return service.failures(size);
    }

    @PostMapping("/failures/{id}/retry")
    public FeishuIntegrationDtos.RunResponse retry(@PathVariable UUID id) {
        return service.retryFailure(id);
    }

    @GetMapping("/events/status")
    public FeishuIntegrationDtos.InboundStatusResponse inboundStatus() {
        return inbound.status();
    }

    @GetMapping("/events")
    public List<FeishuIntegrationDtos.InboundEventResponse> inboundEvents(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int size) {
        return inbound.events(status, size);
    }

    @PostMapping("/events/{id}/retry")
    public ResponseEntity<Void> retryInbound(@PathVariable UUID id) {
        inbound.retry(id);
        return ResponseEntity.noContent().build();
    }
}
