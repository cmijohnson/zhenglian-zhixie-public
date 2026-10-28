package com.yingge.cloud.market.api;

import com.yingge.cloud.market.application.MarketDeadlineService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class MarketDeadlineController {
    private final MarketDeadlineService service;

    public MarketDeadlineController(MarketDeadlineService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/market/deadline-rules")
    public List<MarketDtos.DeadlineRuleResponse> rules() {
        return service.rules();
    }

    @PostMapping("/api/v1/admin/market/deadline-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public MarketDtos.DeadlineRuleResponse create(@Valid @RequestBody MarketDtos.DeadlineRuleRequest request) {
        return service.createRule(request);
    }

    @GetMapping("/api/v1/customers/{customerId}/deadline-shadow")
    public ResponseEntity<MarketDtos.DeadlineSnapshot> snapshot(@PathVariable UUID customerId) {
        return service.findSnapshot(customerId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
