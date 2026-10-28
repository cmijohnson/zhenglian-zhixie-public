package com.yingge.cloud.intelligence.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LocalEmbeddingClient {
    public static final int BGE_M3_DIMENSION = 1_024;
    private final RestClient.Builder builder;

    public LocalEmbeddingClient(RestClient.Builder builder) {
        this.builder = builder;
    }

    public List<float[]> embed(String baseUrl, String model, List<String> texts) {
        if (texts.isEmpty()) return List.of();
        Duration timeout = Duration.ofSeconds(45);
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        RestClient client = builder.clone().baseUrl(trimSlash(baseUrl))
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        try {
            JsonNode response = client.post().uri("/v1/embeddings")
                    .body(Map.of("model", model, "input", texts))
                    .retrieve().body(JsonNode.class);
            if (response == null || !response.path("data").isArray()
                    || response.path("data").size() != texts.size()) {
                throw new EmbeddingUnavailableException("EMBEDDING_INVALID_RESPONSE");
            }
            List<float[]> embeddings = new ArrayList<>();
            for (JsonNode item : response.path("data")) {
                JsonNode vector = item.path("embedding");
                if (!vector.isArray() || vector.size() != BGE_M3_DIMENSION) {
                    throw new EmbeddingUnavailableException("EMBEDDING_DIMENSION_MISMATCH");
                }
                float[] values = new float[BGE_M3_DIMENSION];
                for (int index = 0; index < values.length; index++) values[index] = vector.get(index).floatValue();
                embeddings.add(values);
            }
            return List.copyOf(embeddings);
        } catch (EmbeddingUnavailableException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new EmbeddingUnavailableException("EMBEDDING_SERVICE_UNAVAILABLE");
        }
    }

    private String trimSlash(String value) {
        String resolved = value == null || value.isBlank() ? "http://embedding:8080" : value.trim();
        return resolved.endsWith("/") ? resolved.substring(0, resolved.length() - 1) : resolved;
    }

    public static final class EmbeddingUnavailableException extends RuntimeException {
        private final String code;
        public EmbeddingUnavailableException(String code) {
            super(code);
            this.code = code;
        }
        public String code() { return code; }
    }
}
