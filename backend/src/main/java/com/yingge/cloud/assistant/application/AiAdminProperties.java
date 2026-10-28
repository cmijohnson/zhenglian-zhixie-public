package com.yingge.cloud.assistant.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ai-admin")
public record AiAdminProperties(String masterKey) {}

