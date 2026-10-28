package com.yingge.cloud.assistant.api;

import com.yingge.cloud.assistant.application.AssistantFacade;
import com.yingge.cloud.shared.api.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/assistant")
public class AssistantController {
    private final AssistantFacade assistant;

    public AssistantController(AssistantFacade assistant) {
        this.assistant = assistant;
    }

    @GetMapping("/availability")
    public AssistantDtos.Availability availability() { return assistant.availability(); }

    @PostMapping("/customer-draft")
    public AssistantDtos.CustomerDraft customerDraft(@Valid @RequestBody AssistantDtos.ParseRequest request) {
        return assistant.parseCustomerDraft(request.text());
    }

    @PostMapping("/interaction-draft")
    public AssistantDtos.InteractionDraft interactionDraft(@Valid @RequestBody AssistantDtos.ParseRequest request) {
        return assistant.parseInteractionDraft(request.text());
    }

    @GetMapping(value = "/customers/{customerId}/summary", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter summary(@PathVariable UUID customerId) {
        assistant.requireCustomerAccess(customerId);
        return stream("customer-summary", () -> assistant.summarizeCustomerTimeline(customerId));
    }

    @GetMapping(value = "/customers/{customerId}/next-actions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter nextActions(@PathVariable UUID customerId) {
        assistant.requireCustomerAccess(customerId);
        return stream("next-actions", () -> assistant.recommendNextActions(customerId));
    }

    @GetMapping(value = "/daily-briefing", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter dailyBriefing() {
        return stream("daily-briefing", assistant::generateDailyBriefing);
    }

    @GetMapping(value = "/customers/{customerId}/data-quality", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter dataQuality(@PathVariable UUID customerId) {
        assistant.requireCustomerAccess(customerId);
        return stream("data-quality", () -> assistant.detectDataQualityIssues(customerId));
    }

    private SseEmitter stream(String purpose, TextJob job) {
        SseEmitter emitter = new SseEmitter(180_000L);
        var securityContext = SecurityContextHolder.getContext();
        CompletableFuture.runAsync(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                emitter.send(SseEmitter.event().name("meta").data(Map.of("purpose", purpose)));
                String result = job.run();
                for (int start = 0; start < result.length(); start += 80) {
                    emitter.send(SseEmitter.event().name("delta").data(result.substring(start, Math.min(start + 80, result.length()))));
                }
                emitter.send(SseEmitter.event().name("result").data(Map.of("content", result)));
                emitter.send(SseEmitter.event().name("done").data(Map.of("ok", true)));
                emitter.complete();
            } catch (Exception exception) {
                try {
                    String code = exception instanceof ApiException api ? api.code() : "AI_STREAM_ERROR";
                    String message = exception instanceof ApiException api
                            ? api.getMessage() : "AI 服务暂时无法完成请求";
                    emitter.send(SseEmitter.event().name("error").data(Map.of("code", code, "message", message)));
                    emitter.send(SseEmitter.event().name("done").data(Map.of("ok", false)));
                } catch (IOException ignored) {
                }
                emitter.complete();
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
        return emitter;
    }

    @FunctionalInterface
    private interface TextJob { String run(); }
}
