package com.yingge.cloud.assistant.infrastructure;

import com.yingge.cloud.assistant.application.AiProvider;
import com.yingge.cloud.assistant.application.AiProperties;
import com.yingge.cloud.assistant.application.AiSettingsService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.web.client.RestClient;

public class DynamicAiProvider implements AiProvider {
    private final AiSettingsService settings;
    private final CurrentActorProvider actors;
    private final RestClient.Builder builder;

    public DynamicAiProvider(AiSettingsService settings, CurrentActorProvider actors, RestClient.Builder builder) {
        this.settings = settings;
        this.actors = actors;
        this.builder = builder;
    }

    @Override
    public String providerId() {
        var metadata = metadata();
        return metadata.enabled() && metadata.keyConfigured() ? "openai-compatible" : "disabled";
    }

    @Override
    public String model() {
        return metadata().model();
    }

    @Override
    public boolean available() {
        return config().configured();
    }

    @Override
    public AiResult generate(String systemPrompt, String userPrompt, boolean jsonResponse) {
        var config = config();
        if (!config.enabled()) {
            throw ApiException.unavailable("AI_DISABLED", "智能助手已由管理员关闭");
        }
        if (!config.configured()) {
            throw ApiException.unavailable("AI_PROVIDER_UNAVAILABLE", "智能助手尚未配置可用的 API 密钥");
        }
        return new OpenAiCompatibleProvider(
                new AiProperties(config.baseUrl(), config.apiKey(), config.model(), config.timeout()), builder.clone())
                .generate(systemPrompt, userPrompt, jsonResponse);
    }

    private AiSettingsService.EffectiveConfig config() {
        return settings.effective(actors.require().organizationId());
    }

    private AiSettingsService.RuntimeMetadata metadata() {
        return settings.metadata(actors.require().organizationId());
    }
}
