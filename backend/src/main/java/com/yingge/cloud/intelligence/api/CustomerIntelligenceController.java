package com.yingge.cloud.intelligence.api;

import com.yingge.cloud.intelligence.application.CustomerIntelligenceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/intelligence")
public class CustomerIntelligenceController {
    private final CustomerIntelligenceService service;

    public CustomerIntelligenceController(CustomerIntelligenceService service) {
        this.service = service;
    }

    @GetMapping
    public CustomerIntelligenceDtos.Overview overview(@PathVariable UUID customerId) {
        return service.overview(customerId);
    }

    @GetMapping("/versions")
    public List<CustomerIntelligenceDtos.Version> versions(@PathVariable UUID customerId) {
        return service.versions(customerId);
    }

    @PostMapping("/reanalyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CustomerIntelligenceDtos.Overview reanalyze(@PathVariable UUID customerId) {
        return service.reanalyze(customerId);
    }
}
