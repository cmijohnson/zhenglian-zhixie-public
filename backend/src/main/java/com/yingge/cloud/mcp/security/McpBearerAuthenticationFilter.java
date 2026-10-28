package com.yingge.cloud.mcp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.auth.infrastructure.CloudUserDetailsService;
import com.yingge.cloud.mcp.McpProperties;
import com.yingge.cloud.mcp.oauth.McpOAuthService;
import com.yingge.cloud.mcp.oauth.OAuthProtocolException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

@Component
public class McpBearerAuthenticationFilter extends OncePerRequestFilter {
    private final McpOAuthService oauth;
    private final CloudUserDetailsService users;
    private final McpProperties properties;
    private final McpSessionRegistry sessions;
    private final ObjectMapper mapper;

    public McpBearerAuthenticationFilter(McpOAuthService oauth, CloudUserDetailsService users,
                                         McpProperties properties, McpSessionRegistry sessions,
                                         ObjectMapper mapper) {
        this.oauth = oauth;
        this.users = users;
        this.properties = properties;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals("/mcp") && !request.getRequestURI().startsWith("/mcp/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(response, "invalid_token", "请提供 OAuth Bearer Token");
            return;
        }
        try {
            var token = oauth.authenticate(header.substring(7).trim());
            CloudPrincipal principal = users.loadByMembershipId(token.membershipId());
            var actor = principal.actor();
            if (!actor.userId().equals(token.userId()) || !actor.organizationId().equals(token.organizationId())) {
                throw new OAuthProtocolException("invalid_token", "授权身份与当前成员关系不一致",
                        org.springframework.http.HttpStatus.UNAUTHORIZED);
            }
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, "[MCP_ACCESS_TOKEN]", principal.getAuthorities());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            request.setAttribute(McpAuthenticationAttributes.PRINCIPAL, principal);
            request.setAttribute(McpAuthenticationAttributes.TOKEN, token);
            request.setAttribute(McpAuthenticationAttributes.GRANT_ID, token.grantId());
            request.setAttribute(McpAuthenticationAttributes.CLIENT_ID, token.clientId());
            request.setAttribute(McpAuthenticationAttributes.SCOPES, token.scopes());
            chain.doFilter(request, response);
            if ("DELETE".equalsIgnoreCase(request.getMethod())
                    && response.getStatus() >= 200 && response.getStatus() < 300) {
                sessions.release(token.grantId(), request.getHeader("Mcp-Session-Id"));
            }
        } catch (OAuthProtocolException exception) {
            unauthorized(response, exception.error(), exception.getMessage());
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException exception) {
            unauthorized(response, "invalid_token", exception.getMessage());
        }
    }

    private void unauthorized(HttpServletResponse response, String error, String description) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("WWW-Authenticate", "Bearer resource_metadata=\""
                + properties.normalizedIssuer() + "/.well-known/oauth-protected-resource/mcp\", error=\""
                + error + "\"");
        mapper.writeValue(response.getOutputStream(), Map.of("error", error, "error_description", description));
    }
}
