package com.yingge.cloud.assistant.infrastructure;

import com.yingge.cloud.assistant.application.AiProvider;
import com.yingge.cloud.shared.api.ApiException;

public class DisabledAiProvider implements AiProvider {
    @Override public String providerId() { return "disabled"; }
    @Override public String model() { return "none"; }
    @Override public boolean available() { return false; }

    @Override
    public AiResult generate(String systemPrompt, String userPrompt, boolean jsonResponse) {
        throw ApiException.unavailable("AI_PROVIDER_UNAVAILABLE", "服务器尚未配置模型密钥，客户管理功能不受影响");
    }
}

