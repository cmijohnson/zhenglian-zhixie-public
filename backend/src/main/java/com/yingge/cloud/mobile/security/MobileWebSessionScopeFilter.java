package com.yingge.cloud.mobile.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.shared.api.ApiError;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.api.TraceIdFilter;
import com.yingge.cloud.qq.infrastructure.QqWorkbenchRoutePolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class MobileWebSessionScopeFilter extends OncePerRequestFilter {
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final QqWorkbenchRoutePolicy qqPolicy;

    public MobileWebSessionScopeFilter(ObjectMapper mapper, JdbcTemplate jdbc,
                                       QqWorkbenchRoutePolicy qqPolicy) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.qqPolicy = qqPolicy;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null || session.getAttribute(MobileAuthenticationAttributes.WEB_TARGET_SESSION) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession webSession = request.getSession(false);
        String target = String.valueOf(webSession
                .getAttribute(MobileAuthenticationAttributes.WEB_TARGET_SESSION));
        Object source = webSession.getAttribute(MobileAuthenticationAttributes.WEB_SOURCE_SESSION);
        boolean sourceActive = source instanceof UUID sourceId && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from mobile_device_sessions where id=? and status='ACTIVE')
                """, Boolean.class, sourceId));
        if (!sourceActive) {
            webSession.invalidate();
            writeForbidden(request, response, "MOBILE_WEB_SOURCE_SESSION_REVOKED",
                    "来源移动设备会话已撤销");
            return;
        }
        if (!allowed(target, request.getMethod(), request.getRequestURI())) {
            writeForbidden(request, response, "MOBILE_WEB_SESSION_SCOPE_FORBIDDEN",
                    "该网页会话仅能访问已授权功能");
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response,
                                String code, String message) throws IOException {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            Object trace = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
            mapper.writeValue(response.getOutputStream(), new ApiError(code, message, List.of(),
                    trace == null ? "unknown" : trace.toString(), Instant.now()));
    }

    private boolean allowed(String target, String method, String path) {
        if (Set.of("GET", "HEAD").contains(method)
                && (path.startsWith("/assets/") || path.startsWith("/static/")
                || path.startsWith("/favicon") || path.equals("/manifest.webmanifest"))) return true;
        if (method.equals("GET") && (path.equals("/api/v1/auth/csrf") || path.equals("/api/v1/auth/me"))) {
            return true;
        }
        if (method.equals("POST") && path.equals("/api/v1/auth/logout")) return true;
        return switch (target) {
            case "QQ_WORKBENCH" -> qqAllowed(method, path);
            case "OAUTH_APPROVAL" -> method.equals("GET") && path.equals("/oauth/authorize")
                    || Set.of("GET", "POST").contains(method)
                    && path.matches("^/api/v1/oauth/requests/[0-9a-fA-F-]{36}(?:/(?:approve|deny))?$");
            case "AGENT_APPROVAL" -> method.equals("GET")
                    && (path.equals("/agent") || path.matches("^/agent/approvals/[0-9a-fA-F-]{36}$"))
                    || Set.of("GET", "POST").contains(method)
                    && path.matches("^/api/v1/agent/approvals/[0-9a-fA-F-]{36}(?:/(?:approve|deny))?$");
            default -> false;
        };
    }

    private boolean qqAllowed(String method, String path) {
        if (method.equals("GET") && path.equals("/qq-chat")) return true;
        if (path.equals("/api/v1/qq-connections/me")) return method.equals("GET") || method.equals("DELETE");
        if (path.equals("/api/v1/qq-connections")) return method.equals("POST");
        if (method.equals("GET") && (path.equals("/api/v1/qq-workbench/bootstrap")
                || path.matches("^/api/v1/qq-workbench/media/[A-Za-z0-9_-]+$")
                || path.equals("/api/v1/qq-workbench/public-media")
                || path.matches("^/api/v1/qq-imports/[0-9a-fA-F-]{36}/raw$"))) return true;
        if (method.equals("POST") && (path.equals("/api/v1/qq-imports/preview")
                || path.matches("^/api/v1/qq-imports/[0-9a-fA-F-]{36}/confirm$"))) return true;
        if (path.startsWith("/api/v1/qq-workbench/qce")) {
            String upstream = path.substring("/api/v1/qq-workbench/qce".length());
            if (upstream.isEmpty()) upstream = "/";
            try {
                qqPolicy.requireAllowed(method, upstream);
                return true;
            } catch (ApiException denied) {
                return false;
            }
        }
        return Set.of("GET", "HEAD").contains(method)
                && (path.equals("/qq-workbench") || path.startsWith("/qq-workbench/")
                || path.startsWith("/static/qce/") || path.startsWith("/downloads/")
                || path.startsWith("/scheduled-downloads/") || path.startsWith("/resources/")
                || Set.of("/text-logo.png", "/text-full-logo.png", "/placeholder-logo.png",
                "/placeholder-logo.svg", "/placeholder-user.jpg", "/placeholder.jpg",
                "/placeholder.svg", "/_vercel/insights/script.js").contains(path));
    }
}
