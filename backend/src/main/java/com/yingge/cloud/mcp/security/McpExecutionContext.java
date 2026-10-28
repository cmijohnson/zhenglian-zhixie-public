package com.yingge.cloud.mcp.security;

import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.mcp.oauth.McpOAuthService;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Supplier;

@Component
public class McpExecutionContext {
    private final McpSessionRegistry sessions;

    public McpExecutionContext(McpSessionRegistry sessions) {
        this.sessions = sessions;
    }

    public <T> T call(McpSyncServerExchange exchange, String requiredScope, Supplier<T> action) {
        Object rawPrincipal = exchange.transportContext().get(McpAuthenticationAttributes.PRINCIPAL);
        Object rawToken = exchange.transportContext().get(McpAuthenticationAttributes.TOKEN);
        Object rawTrace = exchange.transportContext().get("yingge.traceId");
        if (!(rawPrincipal instanceof CloudPrincipal principal)
                || !(rawToken instanceof McpOAuthService.TokenAuthentication token)) {
            throw new com.yingge.cloud.shared.api.ApiException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "MCP 授权上下文缺失");
        }
        boolean scopeGranted = token.scopes().contains(requiredScope)
                || ("yingge.admin.read".equals(requiredScope) && token.scopes().contains("yingge.admin"));
        if (!scopeGranted) {
            throw new com.yingge.cloud.shared.api.ApiException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "MCP_SCOPE_REQUIRED",
                    "当前 Agent 未获授权 Scope: " + requiredScope);
        }
        sessions.touch(token.grantId(), exchange.sessionId());
        SecurityContext previous = SecurityContextHolder.getContext();
        String previousTrace = MDC.get("traceId");
        try {
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, "[MCP_ACCESS_TOKEN]", principal.getAuthorities());
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            if (rawTrace != null) MDC.put("traceId", rawTrace.toString());
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
            if (previousTrace == null) MDC.remove("traceId"); else MDC.put("traceId", previousTrace);
        }
    }

    public McpOAuthService.TokenAuthentication token(McpSyncServerExchange exchange) {
        Object value = exchange.transportContext().get(McpAuthenticationAttributes.TOKEN);
        if (value instanceof McpOAuthService.TokenAuthentication token) return token;
        throw new IllegalStateException("MCP token context unavailable");
    }

    public String traceId(McpSyncServerExchange exchange) {
        Object value = exchange.transportContext().get("yingge.traceId");
        return value == null ? UUID.randomUUID().toString().replace("-", "") : value.toString();
    }
}
