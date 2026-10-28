package com.yingge.cloud.business.api;

import com.yingge.cloud.business.application.DealForecastService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/business-cycles/{cycleId}/deal-forecasts")
public class DealForecastController {
    private final DealForecastService service;

    public DealForecastController(DealForecastService service) {
        this.service = service;
    }

    @GetMapping
    public List<DealForecastDtos.Response> list(@PathVariable UUID customerId, @PathVariable UUID cycleId) {
        return service.list(customerId, cycleId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DealForecastDtos.Response create(@PathVariable UUID customerId, @PathVariable UUID cycleId,
                                             @Valid @RequestBody DealForecastDtos.CreateRequest body) {
        return service.create(customerId, cycleId, body);
    }

    @PutMapping("/{forecastId}")
    public DealForecastDtos.Response update(@PathVariable UUID customerId, @PathVariable UUID cycleId,
                                             @PathVariable UUID forecastId,
                                             @Valid @RequestBody DealForecastDtos.UpdateRequest body) {
        return service.update(customerId, cycleId, forecastId, body);
    }

    @PostMapping("/{forecastId}/withdraw")
    public DealForecastDtos.Response withdraw(@PathVariable UUID customerId, @PathVariable UUID cycleId,
                                               @PathVariable UUID forecastId,
                                               @Valid @RequestBody DealForecastDtos.WithdrawRequest body) {
        return service.withdraw(customerId, cycleId, forecastId, body);
    }
}
