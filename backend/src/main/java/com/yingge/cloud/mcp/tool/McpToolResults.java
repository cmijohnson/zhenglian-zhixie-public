package com.yingge.cloud.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class McpToolResults {
    private final ObjectMapper mapper;

    public McpToolResults(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public McpSchema.CallToolResult success(Object data, String traceId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ok", true);
        value.put("data", mapper.convertValue(data, Object.class));
        value.put("traceId", traceId);
        value.put("warnings", List.of());
        return result(value, false);
    }

    public McpSchema.CallToolResult approval(Object approval, String traceId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ok", false);
        value.put("approvalRequired", true);
        value.put("approval", mapper.convertValue(approval, Object.class));
        value.put("traceId", traceId);
        value.put("warnings", List.of("This operation requires an authenticated human confirmation."));
        return result(value, false);
    }

    public McpSchema.CallToolResult error(String code, String message, String traceId, Object fields) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (fields != null) error.put("fields", fields);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("ok", false);
        value.put("error", error);
        value.put("traceId", traceId);
        return result(value, true);
    }

    private McpSchema.CallToolResult result(Map<String, Object> value, boolean error) {
        try {
            String text = mapper.writeValueAsString(value);
            return McpSchema.CallToolResult.builder().addTextContent(text)
                    .structuredContent(value).isError(error).build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize MCP result", exception);
        }
    }
}
