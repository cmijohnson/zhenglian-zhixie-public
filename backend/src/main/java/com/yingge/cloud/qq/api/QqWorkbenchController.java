package com.yingge.cloud.qq.api;

import com.yingge.cloud.qq.application.QqIntegrationService;
import com.yingge.cloud.qq.infrastructure.QqBridgeClient;
import com.yingge.cloud.qq.infrastructure.QqWorkbenchMediaService;
import com.yingge.cloud.qq.infrastructure.QqWorkbenchRoutePolicy;
import com.yingge.cloud.shared.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Set;

@RestController
public class QqWorkbenchController {
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_MESSAGE_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final String QCE_PREFIX = "/api/v1/qq-workbench/qce";
    private static final Set<String> FORWARDED_HEADERS = Set.of(
            "content-type", "content-disposition", "content-length", "last-modified", "etag");

    private final QqIntegrationService service;
    private final QqBridgeClient bridge;
    private final QqWorkbenchRoutePolicy policy;
    private final QqWorkbenchMediaService media;

    public QqWorkbenchController(QqIntegrationService service, QqBridgeClient bridge,
                                 QqWorkbenchRoutePolicy policy, QqWorkbenchMediaService media) {
        this.service = service;
        this.bridge = bridge;
        this.policy = policy;
        this.media = media;
    }

    @GetMapping("/api/v1/qq-workbench/bootstrap")
    public QqDtos.WorkbenchBootstrap bootstrap() { return service.workbenchBootstrap(); }

    @GetMapping("/api/v1/qq-workbench/media/{token}")
    public ResponseEntity<StreamingResponseBody> media(@PathVariable String token) {
        var session = service.requireWorkbenchSession();
        var response = media.fetch(token, session.membershipId(), session.instanceId());
        service.auditWorkbench(session, "GET", "message-media", false, true);
        return mediaResponse(response);
    }

    @GetMapping("/api/v1/qq-workbench/public-media")
    public ResponseEntity<StreamingResponseBody> publicMedia(@RequestParam String kind, @RequestParam String id,
                                                              @RequestParam(defaultValue = "png") String format) {
        var session = service.requireWorkbenchSession();
        var response = media.fetchPublic(kind, id, format);
        return mediaResponse(response);
    }

    @RequestMapping({
            "/api/v1/qq-workbench/qce/**",
            "/qq-workbench", "/qq-workbench/**", "/static/qce/**",
            "/downloads/**", "/scheduled-downloads/**", "/resources/**",
            "/text-logo.png", "/text-full-logo.png", "/placeholder-logo.png", "/placeholder-logo.svg",
            "/placeholder-user.jpg", "/placeholder.jpg", "/placeholder.svg", "/_vercel/insights/script.js"
    })
    public ResponseEntity<StreamingResponseBody> proxy(HttpServletRequest request) throws java.io.IOException {
        String upstreamPath = upstreamPath(request);
        var capability = policy.requireAllowed(request.getMethod(), upstreamPath);
        var session = service.requireWorkbenchSession();
        byte[] body = readBoundedBody(request);
        QqBridgeClient.ProxyResponse response = bridge.proxy(session.instanceId(), request.getMethod(), upstreamPath,
                body, request.getContentType());
        HttpHeaders headers = new HttpHeaders();
        response.headers().forEach((name, values) -> {
            if (FORWARDED_HEADERS.contains(name.toLowerCase())) headers.put(name, values);
        });
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        if (capability.downloadsData()) headers.set("X-Robots-Tag", "noindex, nofollow");
        service.auditWorkbench(session, request.getMethod(), upstreamPath.split("\\?", 2)[0],
                capability.writesData(), capability.downloadsData());
        InputStream responseBody = response.body();
        if (response.status() >= 200 && response.status() < 300
                && headers.getFirst(HttpHeaders.CONTENT_TYPE) != null
                && headers.getFirst(HttpHeaders.CONTENT_TYPE).contains("json")) {
            try (InputStream input = responseBody) {
                byte[] raw = input.readNBytes(MAX_MESSAGE_RESPONSE_BYTES + 1);
                if (raw.length > MAX_MESSAGE_RESPONSE_BYTES) {
                    throw ApiException.badRequest("QQ_WORKBENCH_RESPONSE_TOO_LARGE", "单次 QQ 工作台响应不能超过 16 MB");
                }
                byte[] rewritten = media.rewriteJson(raw, session.membershipId(), session.instanceId());
                responseBody = new ByteArrayInputStream(rewritten);
                headers.setContentLength(rewritten.length);
            }
        }
        InputStream finalResponseBody = responseBody;
        StreamingResponseBody stream = output -> {
            try (InputStream input = finalResponseBody) { input.transferTo(output); }
        };
        return ResponseEntity.status(response.status()).headers(headers).body(stream);
    }

    private ResponseEntity<StreamingResponseBody> mediaResponse(QqWorkbenchMediaService.MediaResponse response) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, response.contentType());
        if (response.contentLength() >= 0) headers.setContentLength(response.contentLength());
        headers.setCacheControl("no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", "default-src 'none'; sandbox");
        StreamingResponseBody stream = output -> {
            try (InputStream input = response.body()) { input.transferTo(output); }
        };
        return ResponseEntity.status(response.status()).headers(headers).body(stream);
    }

    private byte[] readBoundedBody(HttpServletRequest request) throws java.io.IOException {
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) return null;
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            throw ApiException.badRequest("QQ_WORKBENCH_BODY_TOO_LARGE", "QQ 工作台请求体不能超过 2 MB");
        }
        return body;
    }

    private String upstreamPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String path;
        if (uri.startsWith(QCE_PREFIX)) {
            path = uri.substring(QCE_PREFIX.length());
            if (path.isEmpty()) path = "/";
        } else if (uri.equals("/qq-workbench")) {
            path = "/qce";
        } else if (uri.startsWith("/qq-workbench/")) {
            path = "/qce/" + uri.substring("/qq-workbench/".length());
        } else {
            path = uri;
        }
        String query = request.getQueryString();
        return query == null || query.isBlank() ? path : path + "?" + query;
    }
}
