package com.yingge.cloud.assistant.api;

import com.yingge.cloud.assistant.application.AiSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/assistant-settings")
public class AssistantAdminController {
    private final AiSettingsService settings;

    public AssistantAdminController(AiSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public AssistantAdminDtos.SettingsResponse get() {
        return settings.adminSettings();
    }

    @PutMapping
    public AssistantAdminDtos.SettingsResponse update(@Valid @RequestBody AssistantAdminDtos.UpdateRequest request) {
        return settings.update(request);
    }
}

