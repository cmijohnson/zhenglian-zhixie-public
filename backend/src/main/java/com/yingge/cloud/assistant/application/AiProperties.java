package com.yingge.cloud.assistant.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.ai")
public record AiProperties(String baseUrl, String apiKey, String model, Duration timeout) {
    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}

