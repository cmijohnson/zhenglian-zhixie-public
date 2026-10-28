package com.yingge.cloud.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.mcp.tool.McpToolRegistry;
import io.modelcontextprotocol.server.McpNotificationHandler;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerSession;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Small protocol adapter built on the official Java SDK transport/session types.
 * It keeps tools/list principal-aware; the SDK's default server keeps one global list.
 */
public final class YinggeMcpProtocolServer implements AutoCloseable {
    private final HttpServletStreamableServerTransportProvider transport;
    private final ObjectMapper mapper;
    private final McpToolRegistry tools;
    private final Map<String, McpServerFeatures.SyncResourceSpecification> resources;
    private final McpSchema.ServerCapabilities capabilities = McpSchema.ServerCapabilities.builder()
            .tools(false).resources(false, false).build();

    public YinggeMcpProtocolServer(HttpServletStreamableServerTransportProvider transport, ObjectMapper mapper,
                                   McpToolRegistry tools, McpResourceRegistry resourceRegistry) {
        this.transport = transport;
        this.mapper = mapper;
        this.tools = tools;
        Map<String, McpServerFeatures.SyncResourceSpecification> resourceMap = new LinkedHashMap<>();
        resourceRegistry.specifications().forEach(spec -> resourceMap.put(spec.resource().uri(), spec));
        this.resources = Map.copyOf(resourceMap);
        transport.setSessionFactory(this::startSession);
    }

    private McpStreamableServerSession.McpStreamableServerSessionInit startSession(
            McpSchema.InitializeRequest initialize) {
        String sessionId = UUID.randomUUID().toString();
        Map<String, McpRequestHandler<?>> requests = requestHandlers();
        Map<String, McpNotificationHandler> notifications = Map.of(
                McpSchema.METHOD_NOTIFICATION_INITIALIZED, (exchange, params) -> Mono.empty());
        McpStreamableServerSession session = new McpStreamableServerSession(
                sessionId, initialize.capabilities(), initialize.clientInfo(), Duration.ofSeconds(90),
                requests, notifications);
        String version = transport.protocolVersions().contains(initialize.protocolVersion())
                ? initialize.protocolVersion()
                : transport.protocolVersions().get(transport.protocolVersions().size() - 1);
        McpSchema.InitializeResult result = new McpSchema.InitializeResult(version, capabilities,
                new McpSchema.Implementation("yingge-cloud-agent-mcp", "英格客户云 Agent MCP", "1.0.0"),
                "Operate only as the OAuth-authorized employee. Search before writes, use idempotencyKey, "
                        + "and show approvalUrl to the human when approvalRequired is returned.");
        return new McpStreamableServerSession.McpStreamableServerSessionInit(session, Mono.just(result));
    }

    private Map<String, McpRequestHandler<?>> requestHandlers() {
        Map<String, McpRequestHandler<?>> handlers = new LinkedHashMap<>();
        handlers.put(McpSchema.METHOD_PING, (exchange, params) -> Mono.just(Map.of()));
        handlers.put(McpSchema.METHOD_TOOLS_LIST, (exchange, params) -> Mono.just(
                new McpSchema.ListToolsResult(tools.visibleSpecifications(exchange.transportContext()).stream()
                        .map(McpServerFeatures.SyncToolSpecification::tool).toList(), null)));
        handlers.put(McpSchema.METHOD_TOOLS_CALL, (exchange, params) -> {
            McpSchema.CallToolRequest request = mapper.convertValue(params, McpSchema.CallToolRequest.class);
            var specification = tools.visibleSpecifications(exchange.transportContext()).stream()
                    .filter(candidate -> candidate.tool().name().equals(request.name())).findFirst();
            if (specification.isEmpty()) return Mono.error(McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS)
                    .message("Unknown or unauthorized tool").data(Map.of("tool", request.name())).build());
            return Mono.fromCallable(() -> specification.get().callHandler()
                    .apply(new McpSyncServerExchange(exchange), request));
        });
        handlers.put(McpSchema.METHOD_RESOURCES_LIST, (exchange, params) -> Mono.just(
                new McpSchema.ListResourcesResult(resources.values().stream()
                        .map(McpServerFeatures.SyncResourceSpecification::resource).toList(), null)));
        handlers.put(McpSchema.METHOD_RESOURCES_READ, (exchange, params) -> {
            McpSchema.ReadResourceRequest request = mapper.convertValue(params, McpSchema.ReadResourceRequest.class);
            McpServerFeatures.SyncResourceSpecification specification = resources.get(request.uri());
            if (specification == null) return Mono.error(McpError.RESOURCE_NOT_FOUND.apply(request.uri()));
            return Mono.fromCallable(() -> specification.readHandler()
                    .apply(new McpSyncServerExchange(exchange), request));
        });
        handlers.put(McpSchema.METHOD_RESOURCES_TEMPLATES_LIST, (exchange, params) -> Mono.just(
                new McpSchema.ListResourceTemplatesResult(List.of(), null)));
        return Map.copyOf(handlers);
    }

    @Override
    public void close() {
        transport.close();
    }
}
