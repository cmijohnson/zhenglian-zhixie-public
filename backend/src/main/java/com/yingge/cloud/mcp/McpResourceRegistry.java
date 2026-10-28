package com.yingge.cloud.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.mcp.security.McpExecutionContext;
import com.yingge.cloud.mcp.tool.McpToolRegistry;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Component
public class McpResourceRegistry {
    private final ObjectMapper mapper;
    private final McpExecutionContext execution;
    private final McpToolRegistry tools;
    private final McpProperties properties;
    private final Environment environment;

    public McpResourceRegistry(ObjectMapper mapper, McpExecutionContext execution, McpToolRegistry tools,
                               McpProperties properties, Environment environment) {
        this.mapper = mapper;
        this.execution = execution;
        this.tools = tools;
        this.properties = properties;
        this.environment = environment;
    }

    public List<McpServerFeatures.SyncResourceSpecification> specifications() {
        return List.of(
                json("yingge://health", "health", "Health status", "Read-only MCP server health metadata.",
                        exchange -> Map.of("status", "UP", "server", "yingge-cloud-agent-mcp",
                                "protocol", "2025-11-25", "time", Instant.now().toString())),
                json("yingge://openapi", "openapi", "OpenAPI discovery", "Location and access policy of the REST OpenAPI document.",
                        exchange -> Map.of("url", properties.normalizedIssuer() + "/v3/api-docs",
                                "note", "REST OpenAPI is a protected read-only reference; MCP tools call application services directly.")),
                json("yingge://capabilities", "capabilities", "MCP capability catalog",
                        "Explicit typed tool catalog with required scopes.", exchange -> Map.of(
                                "resource", properties.normalizedResourceUri(),
                                "scopes", List.of("yingge.read", "yingge.write", "yingge.ai", "yingge.admin"),
                                "tools", tools.catalog(),
                                "deferred", List.of("CSV/XLSX file transfer"),
                                "controlPlane", List.of("login", "register", "change password", "AI API key entry")))
        );
    }

    private McpServerFeatures.SyncResourceSpecification json(
            String uri, String name, String title, String description,
            java.util.function.Function<io.modelcontextprotocol.server.McpSyncServerExchange, Object> supplier) {
        McpSchema.Resource resource = McpSchema.Resource.builder().uri(uri).name(name).title(title)
                .description(description).mimeType("application/json").build();
        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
                execution.call(exchange, "yingge.read", () -> new McpSchema.ReadResourceResult(List.of(
                        new McpSchema.TextResourceContents(uri, "application/json", json(supplier.apply(exchange)))))));
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Unable to serialize MCP resource", exception); }
    }
}
