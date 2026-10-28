package com.yingge.cloud.business.api;

import com.yingge.cloud.business.application.DealForecastService;
import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.business.domain.DealForecastEvidenceType;
import com.yingge.cloud.business.domain.DealForecastStatus;
import com.yingge.cloud.shared.api.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/deal-forecasts")
public class AdminDealForecastController {
    private final DealForecastService service;

    public AdminDealForecastController(DealForecastService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<DealForecastDtos.Response> search(
            @RequestParam(required = false) DealForecastStatus status,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) UUID ownerMembershipId,
            @RequestParam(required = false) UUID submittedByMembershipId,
            @RequestParam(required = false) LocalDate expectedFrom,
            @RequestParam(required = false) LocalDate expectedTo,
            @RequestParam(required = false) DealForecastEvidenceType evidenceType,
            @RequestParam(required = false) String quality,
            @RequestParam(required = false) Boolean crossSale,
            @RequestParam(required = false) BusinessCycleStatus cycleStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.search(status, teamId, ownerMembershipId, submittedByMembershipId,
                expectedFrom, expectedTo, evidenceType, quality, crossSale, cycleStatus, page, size);
    }

    @GetMapping("/summary")
    public DealForecastDtos.Summary summary() {
        return service.summary();
    }

    @PostMapping("/{forecastId}/confirm")
    public DealForecastDtos.Response confirm(@PathVariable UUID forecastId,
                                              @Valid @RequestBody DealForecastDtos.ConfirmRequest body) {
        return service.confirm(forecastId, body);
    }

    @PostMapping("/{forecastId}/return")
    public DealForecastDtos.Response returnForSupplement(
            @PathVariable UUID forecastId,
            @Valid @RequestBody DealForecastDtos.ReturnRequest body) {
        return service.returnForSupplement(forecastId, body);
    }
}
