package com.yingge.cloud.mcp;

import com.yingge.cloud.mcp.security.McpAuthenticationAttributes;
import com.yingge.cloud.shared.api.TraceIdFilter;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class McpServerConfiguration {
    @Bean
    HttpServletStreamableServerTransportProvider mcpTransport(McpProperties properties) {
        var security = DefaultServerTransportSecurityValidator.builder()
                .allowedOrigins(properties.allowedOrigins())
                .allowedHosts(properties.allowedHosts())
                .build();
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .contextExtractor(this::context)
                .keepAliveInterval(Duration.ofSeconds(20))
                .securityValidator(security)
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
            HttpServletStreamableServerTransportProvider transport) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transport, "/mcp");
        registration.setName("yinggeMcpStreamableHttp");
        registration.setAsyncSupported(true);
        registration.setLoadOnStartup(1);
        return registration;
    }

    @Bean(destroyMethod = "close")
    YinggeMcpProtocolServer mcpServer(HttpServletStreamableServerTransportProvider transport,
                                      com.fasterxml.jackson.databind.ObjectMapper mapper,
                                      com.yingge.cloud.mcp.tool.McpToolRegistry tools,
                                      McpResourceRegistry resources) {
        return new YinggeMcpProtocolServer(transport, mapper, tools, resources);
    }

    private McpTransportContext context(HttpServletRequest request) {
        Map<String, Object> values = new LinkedHashMap<>();
        copy(request, values, McpAuthenticationAttributes.PRINCIPAL);
        copy(request, values, McpAuthenticationAttributes.TOKEN);
        copy(request, values, McpAuthenticationAttributes.GRANT_ID);
        copy(request, values, McpAuthenticationAttributes.CLIENT_ID);
        copy(request, values, McpAuthenticationAttributes.SCOPES);
        copy(request, values, TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return McpTransportContext.create(values);
    }

    private void copy(HttpServletRequest request, Map<String, Object> values, String name) {
        Object value = request.getAttribute(name);
        if (value != null) values.put(name, value);
    }
}
