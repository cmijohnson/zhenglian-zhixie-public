package com.yingge.cloud.deal.api;

import com.yingge.cloud.deal.application.CustomerDealRequestService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/deal-requests")
public class AdminCustomerDealRequestController {
    private final CustomerDealRequestService service;

    public AdminCustomerDealRequestController(CustomerDealRequestService service) {
        this.service = service;
    }

    @GetMapping
    public CustomerDealRequestDtos.Page list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        return service.adminList(status, page, size);
    }

    @PostMapping("/{requestId}/approve")
    public CustomerDealRequestDtos.Response approve(
            @PathVariable UUID requestId,
            @RequestHeader("If-Match-Version") long expectedVersion) {
        return service.approve(requestId, expectedVersion);
    }

    @PostMapping("/{requestId}/reject")
    public CustomerDealRequestDtos.Response reject(
            @PathVariable UUID requestId,
            @RequestHeader("If-Match-Version") long expectedVersion) {
        return service.reject(requestId, expectedVersion);
    }
}
