package com.yingge.cloud.intelligence.infrastructure;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEmbeddingClientContractTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void callsOpenAiCompatibleEndpointAndRequiresBgeM3Dimension() throws Exception {
        String vector = String.join(",", Collections.nCopies(1_024, "0.125"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(body).contains("bge-m3", "客户需求");
            respond(exchange, 200, "{\"data\":[{\"embedding\":[" + vector + "]}]}");
        });
        server.start();

        var result = new LocalEmbeddingClient(RestClient.builder()).embed(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "bge-m3", java.util.List.of("客户需求"));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst()).hasSize(1_024);
        assertThat(result.getFirst()[0]).isEqualTo(0.125f);
    }

    @Test
    void rejectsUnexpectedEmbeddingDimensions() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> respond(exchange, 200,
                "{\"data\":[{\"embedding\":[0.1,0.2]}]}"));
        server.start();

        assertThatThrownBy(() -> new LocalEmbeddingClient(RestClient.builder()).embed(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "bge-m3", java.util.List.of("测试")))
                .isInstanceOfSatisfying(LocalEmbeddingClient.EmbeddingUnavailableException.class,
                        error -> assertThat(error.code()).isEqualTo("EMBEDDING_DIMENSION_MISMATCH"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
