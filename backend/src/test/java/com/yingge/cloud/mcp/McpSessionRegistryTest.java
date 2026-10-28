package com.yingge.cloud.mcp;

import com.yingge.cloud.mcp.security.McpSessionRegistry;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpSessionRegistryTest {
    @Test
    void sixthConcurrentSessionForOneGrantIsRejected() {
        McpProperties properties = new McpProperties(true, "https://example.test",
                "https://example.test/mcp", List.of("https://example.test"), List.of("example.test"),
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(5), Duration.ofMinutes(10),
                Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
        McpSessionRegistry registry = new McpSessionRegistry(properties);
        UUID grantId = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) registry.touch(grantId, "session-" + i);

        assertThatThrownBy(() -> registry.touch(grantId, "session-6"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("MCP_SESSION_LIMIT");
    }

    @Test
    void releasedSessionImmediatelyFreesCapacityForSameGrant() {
        McpProperties properties = new McpProperties(true, "https://example.test",
                "https://example.test/mcp", List.of("https://example.test"), List.of("example.test"),
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(5), Duration.ofMinutes(10),
                Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
        McpSessionRegistry registry = new McpSessionRegistry(properties);
        UUID grantId = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) registry.touch(grantId, "session-" + i);

        registry.release(grantId, "session-3");
        registry.touch(grantId, "session-6");

        assertThatThrownBy(() -> registry.touch(grantId, "session-7"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("MCP_SESSION_LIMIT");
    }

    @Test
    void simultaneousInitializationsCannotExceedFiveSessions() throws Exception {
        McpProperties properties = new McpProperties(true, "https://example.test",
                "https://example.test/mcp", List.of("https://example.test"), List.of("example.test"),
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofMinutes(5), Duration.ofMinutes(10),
                Duration.ofMinutes(5), Duration.ofMinutes(30), 5);
        McpSessionRegistry registry = new McpSessionRegistry(properties);
        UUID grantId = UUID.randomUUID();
        int attempts = 40;
        CountDownLatch ready = new CountDownLatch(attempts);
        CountDownLatch start = new CountDownLatch(1);
        var accepted = new ConcurrentLinkedQueue<String>();
        var rejected = new ConcurrentLinkedQueue<String>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int index = 0; index < attempts; index++) {
                String session = "concurrent-" + index;
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        registry.touch(grantId, session);
                        accepted.add(session);
                    } catch (ApiException expected) {
                        rejected.add(expected.code());
                    }
                    return null;
                });
            }
            ready.await();
            start.countDown();
        }
        assertThat(accepted).hasSize(5);
        assertThat(rejected).hasSize(attempts - 5).containsOnly("MCP_SESSION_LIMIT");
    }
}
