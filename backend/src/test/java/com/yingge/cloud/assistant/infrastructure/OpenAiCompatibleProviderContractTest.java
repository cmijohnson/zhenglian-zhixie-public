package com.yingge.cloud.assistant.infrastructure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yingge.cloud.assistant.application.AiProperties;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleProviderContractTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void parsesValidProviderResponseWithoutExposingTheCredential() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"choices":[{"message":{"content":"合同模型正常"}}],"usage":{"prompt_tokens":7,"completion_tokens":3}}
                """));
        var result = provider(Duration.ofSeconds(2)).generate("system", "user", false);
        assertThat(result.content()).isEqualTo("合同模型正常");
        assertThat(result.inputTokens()).isEqualTo(7);
        assertThat(result.outputTokens()).isEqualTo(3);
    }

    @Test
    void normalizesRateLimitServerErrorInvalidJsonAndEmptyChoices() throws Exception {
        for (Fault fault : new Fault[]{
                new Fault(429, "{\"error\":{\"message\":\"rate limited\"}}"),
                new Fault(500, "{\"error\":{\"message\":\"upstream failure\"}}"),
                new Fault(200, "not-json"),
                new Fault(200, "{\"choices\":[]}")
        }) {
            stopServer();
            start(exchange -> respond(exchange, fault.status(), fault.body()));
            assertThatThrownBy(() -> provider(Duration.ofSeconds(2)).generate("system", "user", true))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.status().value()).isEqualTo(503);
                        assertThat(error.getMessage()).doesNotContain("contract-secret", "upstream failure");
                    });
        }
    }

    @Test
    void normalizesInterruptedProviderBody() throws Exception {
        start(exchange -> {
            byte[] partial = "{\"choices\":[".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, partial.length + 100L);
            exchange.getResponseBody().write(partial);
            exchange.close();
        });
        assertThatThrownBy(() -> provider(Duration.ofSeconds(2)).generate("system", "user", false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_ERROR"));
    }

    @Test
    void normalizesProviderTimeout() throws Exception {
        start(exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThatThrownBy(() -> provider(Duration.ofMillis(50)).generate("system", "user", false))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("AI_PROVIDER_ERROR"));
    }

    private OpenAiCompatibleProvider provider(Duration timeout) {
        var properties = new AiProperties("http://127.0.0.1:" + server.getAddress().getPort(),
                "contract-secret", "contract-model", timeout);
        return new OpenAiCompatibleProvider(properties, RestClient.builder());
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> handler.handle(exchange));
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record Fault(int status, String body) {}

    @FunctionalInterface
    private interface Handler { void handle(HttpExchange exchange) throws IOException; }
}
