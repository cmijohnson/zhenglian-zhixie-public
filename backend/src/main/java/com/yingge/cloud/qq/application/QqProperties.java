package com.yingge.cloud.qq.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.qq")
public record QqProperties(
        String bridgeBaseUrl,
        String bridgeToken,
        String masterKey,
        String retentionDir,
        int retentionDays,
        int maxMessagesPerImport
) {}
