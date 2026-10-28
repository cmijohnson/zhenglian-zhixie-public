package com.yingge.cloud.deal.api;

import com.yingge.cloud.deal.application.DealConfirmationService;
import com.yingge.cloud.feishu.api.FeishuDealBotDtos;
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
@RequestMapping("/api/v1/admin")
public class AdminDealConfirmationController {
    private final DealConfirmationService service;

    public AdminDealConfirmationController(DealConfirmationService service) {
        this.service = service;
    }

    @GetMapping("/deal-confirmations")
    public FeishuDealBotDtos.ConfirmationPage list(@RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return service.list(status, false, page, size);
    }

    @GetMapping("/deal-confirmations/legacy")
    public FeishuDealBotDtos.ConfirmationPage legacy(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        return service.list("APPROVED", true, page, size);
    }

    @PostMapping("/deal-confirmations/{id}/reverse")
    public FeishuDealBotDtos.ConfirmationResponse reverse(@PathVariable UUID id,
                                                          @Valid @RequestBody FeishuDealBotDtos.ReversalRequest body) {
        return service.reverse(id, body);
    }

    @PostMapping("/deal-confirmations/{id}/complete-attribution")
    public FeishuDealBotDtos.ConfirmationResponse completeAttribution(
            @PathVariable UUID id,
            @Valid @RequestBody FeishuDealBotDtos.CompleteAttributionRequest body) {
        return service.completeAttribution(id, body);
    }

    @GetMapping("/deal-leaderboard")
    public FeishuDealBotDtos.LeaderboardResponse leaderboard(@RequestParam(required = false) LocalDate date) {
        return service.leaderboard(date);
    }
}
