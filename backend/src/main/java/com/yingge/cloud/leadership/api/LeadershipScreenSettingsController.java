package com.yingge.cloud.leadership.api;

import com.yingge.cloud.leadership.application.LeadershipScreenSettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/leadership-screen-settings")
public class LeadershipScreenSettingsController {
    private final LeadershipScreenSettingsService settings;

    public LeadershipScreenSettingsController(LeadershipScreenSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public LeadershipScreenSettingsDtos.Settings get() {
        return settings.adminSettings();
    }

    @PutMapping
    public LeadershipScreenSettingsDtos.Settings update(
            @Valid @RequestBody LeadershipScreenSettingsDtos.UpdateRequest request) {
        return settings.update(request);
    }
}
