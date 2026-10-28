package com.yingge.cloud.mcp.security;

import com.yingge.cloud.mcp.McpProperties;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpSessionRegistry {
    private final McpProperties properties;
    private final Map<UUID, Map<String, Instant>> sessions = new ConcurrentHashMap<>();

    public McpSessionRegistry(McpProperties properties) {
        this.properties = properties;
    }

    public void touch(UUID grantId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        Instant cutoff = Instant.now().minus(properties.sessionIdleTtl());
        sessions.compute(grantId, (ignored, existing) -> {
            Map<String, Instant> grantSessions = existing == null ? new ConcurrentHashMap<>() : existing;
            grantSessions.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
            if (!grantSessions.containsKey(sessionId) && grantSessions.size() >= properties.maxSessionsPerGrant()) {
                throw ApiException.conflict("MCP_SESSION_LIMIT", "当前 Agent 授权已达到最大并发会话数");
            }
            grantSessions.put(sessionId, Instant.now());
            return grantSessions;
        });
    }

    public void release(UUID grantId, String sessionId) {
        if (grantId == null || sessionId == null || sessionId.isBlank()) return;
        sessions.computeIfPresent(grantId, (ignored, grantSessions) -> {
            grantSessions.remove(sessionId);
            return grantSessions.isEmpty() ? null : grantSessions;
        });
    }

    @Scheduled(fixedDelay = 300_000)
    public void cleanup() {
        Instant cutoff = Instant.now().minus(properties.sessionIdleTtl());
        sessions.keySet().forEach(grantId -> sessions.computeIfPresent(grantId, (ignored, grantSessions) -> {
            grantSessions.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
            return grantSessions.isEmpty() ? null : grantSessions;
        }));
    }
}
