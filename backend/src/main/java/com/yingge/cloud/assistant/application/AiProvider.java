package com.yingge.cloud.assistant.application;

public interface AiProvider {
    String providerId();
    String model();
    boolean available();
    AiResult generate(String systemPrompt, String userPrompt, boolean jsonResponse);

    record AiResult(String content, Integer inputTokens, Integer outputTokens) {}
}

