package com.yingge.cloud.qq.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class QqWorkbenchMediaService {
    private static final long TOKEN_TTL_SECONDS = 300;
    private static final long MAX_MEDIA_BYTES = 50L * 1024 * 1024;
    private static final Set<String> MEDIA_KEYS = Set.of(
            "originimageurl", "sourceurl", "thumburl", "url", "downloadurl", "avatarurl",
            "audiourl", "ptturl", "videourl", "fileurl", "sourcepath", "thumbpath", "filepath",
            "relativepath", "originalfilepath");
    private static final Set<String> SAFE_DOWNLOAD_TYPES = Set.of(
            "application/json", "application/pdf", "application/zip", "application/x-zip-compressed",
            "application/gzip", "application/x-gzip", "application/xml", "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final List<String> ALLOWED_HOST_SUFFIXES = List.of(
            "qq.com", "qq.com.cn", "qpic.cn", "gtimg.cn", "qlogo.cn");

    private final ObjectMapper objectMapper;
    private final QqDataCipher cipher;
    private final QqBridgeClient bridge;
    private final QqWorkbenchRoutePolicy policy;
    private final HttpClient httpClient;

    @Autowired
    public QqWorkbenchMediaService(ObjectMapper objectMapper, QqDataCipher cipher, QqBridgeClient bridge,
                                   QqWorkbenchRoutePolicy policy) {
        this.objectMapper = objectMapper;
        this.cipher = cipher;
        this.bridge = bridge;
        this.policy = policy;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    QqWorkbenchMediaService(ObjectMapper objectMapper, QqDataCipher cipher) {
        this(objectMapper, cipher, null, new QqWorkbenchRoutePolicy());
    }

    public byte[] rewriteJson(byte[] body, UUID membershipId, String instanceId) {
        try {
            JsonNode root = objectMapper.readTree(body);
            rewrite(root, membershipId, instanceId);
            return objectMapper.writeValueAsBytes(root);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiException.unavailable("QQ_MEDIA_REWRITE_FAILED", "QQ 媒体地址暂时无法安全处理");
        }
    }

    public String rewriteWebSocketText(String body, UUID membershipId, String instanceId) {
        if (body == null || body.isBlank()) return body;
        if (body.length() > 8 * 1024 * 1024) {
            throw ApiException.badRequest("QQ_WORKBENCH_RESPONSE_TOO_LARGE", "QQ 工作台实时消息不能超过 8 MB");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            rewrite(root, membershipId, instanceId);
            return objectMapper.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            return body;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiException.unavailable("QQ_MEDIA_REWRITE_FAILED", "QQ 媒体地址暂时无法安全处理");
        }
    }

    public MediaResponse fetch(String token, UUID membershipId, String instanceId) {
        MediaTicket ticket = decode(token, membershipId);
        if ("QCE".equals(ticket.kind())) {
            if (!instanceId.equals(ticket.instanceId())) throw ApiException.forbidden("QQ 媒体凭据已失效");
            return fetchQce(ticket.source(), instanceId);
        }
        return fetchUrl(validatedSource(ticket.source()), 0);
    }

    public MediaResponse fetchPublic(String kind, String id, String format) {
        String safeId = id == null ? "" : id.trim();
        URI source;
        if ("market-face".equals(kind) && safeId.matches("[A-Za-z0-9_-]{2,120}")) {
            String extension = "gif".equalsIgnoreCase(format) ? "gif" : "png";
            source = URI.create("https://gxh.vip.qq.com/club/item/parcel/item/" + safeId.substring(0, 2)
                    + "/" + safeId + "/raw300." + extension);
        } else if ("qq-face".equals(kind) && safeId.matches("[0-9]{1,8}")) {
            source = URI.create("https://res.qlogo.cn/qqface/" + safeId + "/100");
        } else if ("qq-avatar".equals(kind) && safeId.matches("[0-9]{4,15}")) {
            source = URI.create("https://q1.qlogo.cn/g?b=qq&nk=" + safeId + "&s=100");
        } else if ("group-avatar".equals(kind) && safeId.matches("[0-9]{4,15}")) {
            source = URI.create("https://p.qlogo.cn/gh/" + safeId + "/" + safeId + "/100");
        } else {
            throw ApiException.badRequest("QQ_MEDIA_REFERENCE_INVALID", "QQ 表情引用无效");
        }
        return fetchUrl(source, 0);
    }

    private void rewrite(JsonNode node, UUID membershipId, String instanceId) {
        if (node instanceof ArrayNode array) {
            array.forEach(child -> rewrite(child, membershipId, instanceId));
            return;
        }
        if (!(node instanceof ObjectNode object)) return;
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        object.properties().forEach(entries::add);
        entries.forEach(entry -> {
            JsonNode value = entry.getValue();
            if (value.isContainerNode()) {
                rewrite(value, membershipId, instanceId);
                return;
            }
            if (!value.isTextual() || !MEDIA_KEYS.contains(entry.getKey().toLowerCase(Locale.ROOT))) return;
            String raw = value.asText();
            MediaTicket ticket;
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                ticket = new MediaTicket(membershipId, "EXTERNAL", validatedSource(raw).toString(), null,
                        Instant.now().plusSeconds(TOKEN_TTL_SECONDS).getEpochSecond());
            } else if (isQceMediaPath(raw)) {
                policy.requireAllowedMediaSource(raw);
                ticket = new MediaTicket(membershipId, "QCE", raw, instanceId,
                        Instant.now().plusSeconds(TOKEN_TTL_SECONDS).getEpochSecond());
            } else if (entry.getKey().toLowerCase(Locale.ROOT).endsWith("path") && !raw.isBlank()) {
                String downloadPath = "/api/download-file?path=" + URLEncoder.encode(raw, StandardCharsets.UTF_8);
                policy.requireAllowedMediaSource(downloadPath);
                ticket = new MediaTicket(membershipId, "QCE", downloadPath, instanceId,
                        Instant.now().plusSeconds(TOKEN_TTL_SECONDS).getEpochSecond());
            } else {
                return;
            }
            try {
                String encoded = cipher.encryptOpaque(objectMapper.writeValueAsString(ticket));
                object.put(entry.getKey(), "/api/v1/qq-workbench/media/" + encoded);
            } catch (Exception exception) {
                throw ApiException.unavailable("QQ_MEDIA_TOKEN_FAILED", "QQ 媒体凭据暂时无法生成");
            }
        });
    }

    private MediaTicket decode(String token, UUID membershipId) {
        try {
            MediaTicket ticket = objectMapper.readValue(cipher.decryptOpaque(token), MediaTicket.class);
            if (!membershipId.equals(ticket.membershipId()) || ticket.expiresAt() < Instant.now().getEpochSecond()) {
                throw ApiException.forbidden("QQ 媒体凭据已失效");
            }
            if (!List.of("EXTERNAL", "QCE").contains(ticket.kind())) throw new IllegalArgumentException();
            return ticket;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiException.badRequest("QQ_MEDIA_TOKEN_INVALID", "QQ 媒体凭据无效");
        }
    }

    private MediaResponse fetchUrl(URI source, int redirects) {
        if (redirects > 2) throw ApiException.unavailable("QQ_MEDIA_REDIRECT_LIMIT", "QQ 媒体重定向次数过多");
        try {
            HttpRequest request = HttpRequest.newBuilder(source).timeout(Duration.ofSeconds(30))
                    .header(HttpHeaders.ACCEPT, "image/*,audio/*,video/*,application/octet-stream;q=0.5")
                    .GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                String location = response.headers().firstValue(HttpHeaders.LOCATION).orElseThrow();
                response.body().close();
                return fetchUrl(validatedSource(source.resolve(location).toString()), redirects + 1);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw ApiException.unavailable("QQ_MEDIA_UPSTREAM_FAILED", "QQ 媒体暂时不可用");
            }
            long length = response.headers().firstValueAsLong(HttpHeaders.CONTENT_LENGTH).orElse(-1);
            if (length > MAX_MEDIA_BYTES) {
                response.body().close();
                throw ApiException.badRequest("QQ_MEDIA_TOO_LARGE", "QQ 媒体不能超过 50 MB");
            }
            String contentType = response.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse("application/octet-stream")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!(contentType.startsWith("image/") || contentType.startsWith("audio/")
                    || contentType.startsWith("video/") || contentType.equals("application/octet-stream"))) {
                response.body().close();
                throw ApiException.badRequest("QQ_MEDIA_TYPE_FORBIDDEN", "该 QQ 媒体类型不允许预览");
            }
            return new MediaResponse(response.statusCode(), contentType, length, new LimitedInputStream(response.body()));
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.unavailable("QQ_MEDIA_UPSTREAM_FAILED", "QQ 媒体暂时不可用");
        } catch (Exception exception) {
            throw ApiException.unavailable("QQ_MEDIA_UPSTREAM_FAILED", "QQ 媒体暂时不可用");
        }
    }

    private MediaResponse fetchQce(String source, String instanceId) {
        if (bridge == null) throw ApiException.unavailable("QQ_MEDIA_UPSTREAM_FAILED", "QQ 媒体暂时不可用");
        policy.requireAllowedMediaSource(source);
        QqBridgeClient.ProxyResponse response = bridge.proxy(instanceId, "GET", source, null, null);
        try {
            if (response.status() < 200 || response.status() >= 300) {
                response.body().close();
                throw ApiException.unavailable("QQ_MEDIA_UPSTREAM_FAILED", "QQ 媒体暂时不可用");
            }
            String contentType = firstHeader(response.headers(), HttpHeaders.CONTENT_TYPE, "application/octet-stream")
                    .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!isAllowedQceContentType(contentType)) {
                response.body().close();
                throw ApiException.badRequest("QQ_MEDIA_TYPE_FORBIDDEN", "该 QQ 媒体类型不允许预览");
            }
            long length;
            try { length = Long.parseLong(firstHeader(response.headers(), HttpHeaders.CONTENT_LENGTH, "-1")); }
            catch (NumberFormatException ignored) { length = -1; }
            if (length > MAX_MEDIA_BYTES) {
                response.body().close();
                throw ApiException.badRequest("QQ_MEDIA_TOO_LARGE", "QQ 媒体不能超过 50 MB");
            }
            return new MediaResponse(response.status(), contentType, length, new LimitedInputStream(response.body()));
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ApiException.unavailable("QQ_MEDIA_UPSTREAM_FAILED", "QQ 媒体暂时不可用");
        }
    }

    private boolean isQceMediaPath(String value) {
        return value.startsWith("/api/download-file?") || value.startsWith("/downloads/")
                || value.startsWith("/scheduled-downloads/") || value.startsWith("/resources/");
    }

    static boolean isAllowedQceContentType(String contentType) {
        return contentType.startsWith("image/") || contentType.startsWith("audio/")
                || contentType.startsWith("video/") || contentType.startsWith("text/")
                || contentType.equals("application/octet-stream") || SAFE_DOWNLOAD_TYPES.contains(contentType);
    }

    private String firstHeader(Map<String, List<String>> headers, String name, String fallback) {
        return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream()).findFirst().orElse(fallback);
    }

    private URI validatedSource(String value) {
        try {
            URI parsed = URI.create(value);
            if (!List.of("http", "https").contains(parsed.getScheme()) || parsed.getUserInfo() != null
                    || parsed.getHost() == null || parsed.getPort() != -1) throw new IllegalArgumentException();
            String host = parsed.getHost().toLowerCase(Locale.ROOT);
            if (ALLOWED_HOST_SUFFIXES.stream().noneMatch(suffix -> host.equals(suffix) || host.endsWith("." + suffix))) {
                throw new IllegalArgumentException();
            }
            return "http".equals(parsed.getScheme()) ? URI.create("https" + value.substring(4)) : parsed;
        } catch (Exception exception) {
            throw ApiException.badRequest("QQ_MEDIA_SOURCE_FORBIDDEN", "QQ 媒体来源不受信任");
        }
    }

    private record MediaTicket(UUID membershipId, String kind, String source, String instanceId, long expiresAt) {}
    public record MediaResponse(int status, String contentType, long contentLength, InputStream body) {}

    private static final class LimitedInputStream extends FilterInputStream {
        private long count;
        private LimitedInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) account(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) account(read);
            return read;
        }
        private void account(int bytes) throws IOException {
            count += bytes;
            if (count > MAX_MEDIA_BYTES) throw new IOException("QQ media exceeded 50 MB limit");
        }
    }
}
