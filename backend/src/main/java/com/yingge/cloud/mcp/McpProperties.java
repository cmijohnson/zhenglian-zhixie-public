package com.yingge.cloud.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("app.mcp")
public record McpProperties(
        boolean enabled,
        String issuer,
        String resourceUri,
        List<String> allowedOrigins,
        List<String> allowedHosts,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration authorizationCodeTtl,
        Duration authorizationRequestTtl,
        Duration approvalTtl,
        Duration sessionIdleTtl,
        int maxSessionsPerGrant
) {
    public McpProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        accessTokenTtl = fallback(accessTokenTtl, Duration.ofMinutes(15));
        refreshTokenTtl = fallback(refreshTokenTtl, Duration.ofDays(30));
        authorizationCodeTtl = fallback(authorizationCodeTtl, Duration.ofMinutes(5));
        authorizationRequestTtl = fallback(authorizationRequestTtl, Duration.ofMinutes(10));
        approvalTtl = fallback(approvalTtl, Duration.ofMinutes(5));
        sessionIdleTtl = fallback(sessionIdleTtl, Duration.ofMinutes(30));
        maxSessionsPerGrant = maxSessionsPerGrant <= 0 ? 5 : maxSessionsPerGrant;
    }

    private static Duration fallback(Duration value, Duration defaultValue) {
        return value == null || value.isNegative() || value.isZero() ? defaultValue : value;
    }

    public String normalizedIssuer() {
        return trimTrailingSlash(issuer == null || issuer.isBlank() ? "http://localhost:8080" : issuer);
    }

    public String normalizedResourceUri() {
        return resourceUri == null || resourceUri.isBlank()
                ? normalizedIssuer() + "/mcp"
                : trimTrailingSlash(resourceUri);
    }

    private static String trimTrailingSlash(String value) {
        return value.replaceFirst("/+$", "");
    }
}
