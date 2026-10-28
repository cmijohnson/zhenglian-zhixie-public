package com.yingge.cloud.feishu.api;

import com.yingge.cloud.feishu.application.FeishuDealBotAdminService;
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
@RequestMapping("/api/v1/admin")
public class FeishuDealBotAdminController {
    private final FeishuDealBotAdminService service;

    public FeishuDealBotAdminController(FeishuDealBotAdminService service) {
        this.service = service;
    }

    @GetMapping("/feishu-deal-bot")
    public ResponseEntity<FeishuDealBotDtos.SettingsResponse> configuration() {
        FeishuDealBotDtos.SettingsResponse response = service.configuration();
        return response == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
    }

    @PutMapping("/feishu-deal-bot")
    public FeishuDealBotDtos.SettingsResponse save(@Valid @RequestBody FeishuDealBotDtos.SettingsRequest body) {
        return service.save(body);
    }

    @GetMapping("/feishu-deal-bot/chats")
    public List<FeishuDealBotDtos.ChatResponse> chats() {
        return service.chats();
    }

    @PostMapping("/feishu-deal-bot/test")
    public FeishuDealBotDtos.TestResponse test() {
        return service.test();
    }

    @PostMapping("/feishu-deal-bot/publish-leaderboard")
    public FeishuDealBotDtos.LeaderboardResponse publishLeaderboard() {
        return service.publishLeaderboard();
    }

    @GetMapping("/feishu-message-failures")
    public List<FeishuDealBotDtos.MessageFailureResponse> failures(@RequestParam(defaultValue = "100") int size) {
        return service.failures(size);
    }

    @PostMapping("/feishu-message-failures/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable UUID id) {
        service.retry(id);
        return ResponseEntity.noContent().build();
    }
}
