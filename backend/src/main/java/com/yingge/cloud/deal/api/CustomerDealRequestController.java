package com.yingge.cloud.deal.api;

import com.yingge.cloud.deal.application.CustomerDealRequestService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}/deal-requests")
public class CustomerDealRequestController {
    private final CustomerDealRequestService service;

    public CustomerDealRequestController(CustomerDealRequestService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDealRequestDtos.Response request(
            @PathVariable UUID customerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.request(customerId, idempotencyKey);
    }
}
