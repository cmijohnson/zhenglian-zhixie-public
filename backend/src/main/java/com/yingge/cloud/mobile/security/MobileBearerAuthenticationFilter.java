package com.yingge.cloud.mobile.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.auth.infrastructure.CloudUserDetailsService;
import com.yingge.cloud.mobile.application.MobileAuthService;
import com.yingge.cloud.shared.api.ApiError;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.api.TraceIdFilter;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.yingge.cloud.mobile.api.MobileDtos;

@Component
public class MobileBearerAuthenticationFilter extends OncePerRequestFilter {
    private final MobileAuthService tokens;
    private final CloudUserDetailsService users;
    private final ObjectMapper mapper;

    public MobileBearerAuthenticationFilter(MobileAuthService tokens, CloudUserDetailsService users,
                                            ObjectMapper mapper) {
        this.tokens = tokens;
        this.users = users;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return !request.getRequestURI().startsWith("/api/v1/") || authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            String raw = request.getHeader("Authorization").substring(7).trim();
            UUID installationId = UUID.fromString(requiredHeader(request, "X-Yingge-Device-Id"));
            MobileDtos.Platform platform = MobileDtos.Platform.valueOf(
                    requiredHeader(request, "X-Yingge-Platform").toUpperCase(java.util.Locale.ROOT));
            String appVersion = requiredHeader(request, "X-Yingge-App-Version");
            long versionCode = Long.parseLong(requiredHeader(request, "X-Yingge-Version-Code"));
            MobileAuthService.AccessAuthentication token = tokens.authenticateAccess(
                    raw, installationId, platform, appVersion, versionCode);
            CloudPrincipal principal = users.loadByMembershipId(token.membershipId());
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, principal.getAuthorities());
            authentication.setDetails(token.sessionId());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            request.setAttribute(MobileAuthenticationAttributes.SESSION_ID, token.sessionId());
            request.setAttribute(MobileAuthenticationAttributes.INSTALLATION_ID, token.installationId());
            chain.doFilter(request, response);
        } catch (ApiException exception) {
            write(request, response, exception.status().value(), exception.code(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            write(request, response, HttpServletResponse.SC_BAD_REQUEST, "MOBILE_DEVICE_METADATA_INVALID",
                    "移动设备信息不完整");
        } catch (RuntimeException exception) {
            write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "MOBILE_ACCESS_TOKEN_INVALID",
                    "访问凭据无效或已过期");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private String requiredHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing mobile header");
        return value.trim();
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Object trace = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message, List.of(),
                trace == null ? "unknown" : trace.toString(), Instant.now()));
    }
}
