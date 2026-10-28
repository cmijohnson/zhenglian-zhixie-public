package com.yingge.cloud.assistant.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.yingge.cloud.assistant.application.AiProperties;
import com.yingge.cloud.assistant.application.AiProvider;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiCompatibleProvider implements AiProvider {
    private final AiProperties properties;
    private final RestClient client;

    public OpenAiCompatibleProvider(AiProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        Duration timeout = properties.timeout() == null ? Duration.ofSeconds(45) : properties.timeout();
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.client = builder.baseUrl(trimSlash(properties.baseUrl()))
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override public String providerId() { return "openai-compatible"; }
    @Override public String model() { return properties.model(); }
    @Override public boolean available() { return true; }

    @Override
    public AiResult generate(String systemPrompt, String userPrompt, boolean jsonResponse) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", 0.2);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        if (jsonResponse) body.put("response_format", Map.of("type", "json_object"));
        try {
            JsonNode response = client.post().uri("/chat/completions").body(body).retrieve().body(JsonNode.class);
            if (response == null || response.path("choices").isEmpty()) {
                throw ApiException.unavailable("AI_EMPTY_RESPONSE", "模型未返回可用结果");
            }
            String content = response.path("choices").get(0).path("message").path("content").asText();
            Integer input = response.path("usage").has("prompt_tokens") ? response.path("usage").path("prompt_tokens").asInt() : null;
            Integer output = response.path("usage").has("completion_tokens") ? response.path("usage").path("completion_tokens").asInt() : null;
            return new AiResult(content, input, output);
        } catch (ApiException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw ApiException.unavailable("AI_PROVIDER_ERROR", "模型服务暂时不可用");
        }
    }

    private String trimSlash(String value) {
        String resolved = value == null || value.isBlank() ? "https://api.openai.com/v1" : value.trim();
        return resolved.endsWith("/") ? resolved.substring(0, resolved.length() - 1) : resolved;
    }
}
