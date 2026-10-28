package com.yingge.cloud.market.api;

import com.yingge.cloud.market.application.MarketClassificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class MarketClassificationController {
    private final MarketClassificationService service;

    public MarketClassificationController(MarketClassificationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/customers/{customerId}/classifications")
    public List<MarketDtos.ClassificationResponse> history(@PathVariable UUID customerId) {
        return service.history(customerId);
    }

    @GetMapping("/api/v1/customers/{customerId}/classifications/current")
    public MarketDtos.ClassificationResponse current(@PathVariable UUID customerId) {
        return service.current(customerId);
    }

    @PostMapping("/api/v1/customers/{customerId}/classifications")
    @ResponseStatus(HttpStatus.CREATED)
    public MarketDtos.ClassificationResponse classify(@PathVariable UUID customerId,
                                                       @Valid @RequestBody MarketDtos.ClassificationRequest request) {
        return service.classify(customerId, request);
    }

    @GetMapping("/api/v1/admin/market/classification-reviews")
    public List<MarketDtos.ReviewQueueItem> reviewQueue(
            @RequestParam(required = false) LocalDate month,
            @RequestParam(defaultValue = "true") boolean recommendedOnly) {
        return service.reviewQueue(month, recommendedOnly);
    }

    @PostMapping("/api/v1/admin/market/classification-reviews/{classificationId}/select")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void select(@PathVariable UUID classificationId) {
        service.selectForReview(classificationId);
    }

    @PostMapping("/api/v1/admin/market/classification-reviews/{classificationId}/review")
    public MarketDtos.ClassificationResponse review(@PathVariable UUID classificationId,
                                                     @Valid @RequestBody MarketDtos.ReviewRequest request) {
        return service.review(classificationId, request);
    }

    @GetMapping("/api/v1/admin/market/classification-anomalies")
    public List<MarketDtos.MemberAnomaly> anomalies() {
        return service.anomalies();
    }

    @GetMapping("/api/v1/admin/market/classification-data-issues")
    public List<MarketDtos.ClassificationDataIssue> dataIssues() {
        return service.dataIssues();
    }
}
