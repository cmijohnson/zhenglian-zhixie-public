package com.yingge.cloud.assistant.infrastructure;

import com.yingge.cloud.assistant.application.AiProvider;
import com.yingge.cloud.assistant.application.AiSettingsService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AiProviderConfiguration {
    @Bean
    AiProvider aiProvider(AiSettingsService settings, CurrentActorProvider actors, RestClient.Builder builder) {
        return new DynamicAiProvider(settings, actors, builder);
    }
}
