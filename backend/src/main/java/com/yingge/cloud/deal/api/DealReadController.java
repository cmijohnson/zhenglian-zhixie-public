package com.yingge.cloud.deal.api;

import com.yingge.cloud.deal.application.DealConfirmationService;
import com.yingge.cloud.feishu.api.FeishuDealBotDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import com.yingge.cloud.shared.api.ApiException;

@RestController
@RequestMapping("/api/v1")
public class DealReadController {
    private final DealConfirmationService service;

    public DealReadController(DealConfirmationService service) {
        this.service = service;
    }

    @GetMapping("/customers/{customerId}/deal-summary")
    public FeishuDealBotDtos.DealSummaryResponse dealSummary(@PathVariable UUID customerId) {
        return service.dealSummary(customerId);
    }

    @GetMapping("/deal-leaderboard")
    public FeishuDealBotDtos.LeaderboardResponse leaderboard(@RequestParam(required = false) String month) {
        try {
            LocalDate date = month == null || month.isBlank()
                    ? LocalDate.now(ZoneId.of("Asia/Shanghai")) : YearMonth.parse(month).atDay(1);
            return service.leaderboardForCurrentActor(date);
        } catch (DateTimeParseException exception) {
            throw ApiException.badRequest("DEAL_MONTH_INVALID", "月份格式应为 YYYY-MM");
        }
    }
}
