package com.yingge.cloud.qq.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.yingge.cloud.qq.application.QqProperties;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.io.InputStream;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class QqBridgeClient {
    private static final Logger log = LoggerFactory.getLogger(QqBridgeClient.class);
    private final QqProperties properties;
    private final RestClient client;
    private final HttpClient transport;

    @Autowired
    public QqBridgeClient(QqProperties properties, RestClient.Builder builder) {
        this(properties, builder, true);
    }

    QqBridgeClient(QqProperties properties, RestClient.Builder builder, boolean applyTimeout) {
        this.properties = properties;
        // The Bridge is an internal Node/Express HTTP/1.1 service. Forcing the
        // protocol avoids h2c upgrade/reuse failures where the JDK client can
        // report "header parser received no bytes" before Express responds.
        this.transport = newBridgeTransport();
        if (applyTimeout) {
            Duration timeout = Duration.ofSeconds(40);
            var requestFactory = new JdkClientHttpRequestFactory(
                    transport);
            requestFactory.setReadTimeout(timeout);
            builder.requestFactory(requestFactory);
        }
        this.client = properties.bridgeBaseUrl() == null || properties.bridgeBaseUrl().isBlank()
                ? null
                : builder.baseUrl(properties.bridgeBaseUrl().replaceAll("/+$", ""))
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.bridgeToken())
                    .build();
    }

    static HttpClient newBridgeTransport() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean configured() {
        return client != null && properties.bridgeToken() != null && !properties.bridgeToken().isBlank();
    }

    public JsonNode create(String key) { return post("/v1/instances", Map.of("key", key)); }
    public JsonNode status(String instanceId) { return get("/v1/instances/" + instanceId); }
    public void revoke(String instanceId) {
        requireClient().delete().uri("/v1/instances/{id}", instanceId).retrieve().toBodilessEntity();
    }
    public JsonNode friends(String instanceId, int page, int limit) {
        return get("/v1/instances/" + instanceId + "/friends?page=" + page + "&limit=" + limit);
    }
    public JsonNode messages(String instanceId, int chatType, String peerUid, long startTime, long endTime, int page, int limit) {
        return post("/v1/instances/" + instanceId + "/messages", Map.of(
                "chatType", chatType, "peerUid", peerUid, "startTime", startTime, "endTime", endTime, "page", page, "limit", limit));
    }

    public ProxyResponse proxy(String instanceId, String method, String upstreamPath, byte[] body, String contentType) {
        if (!configured()) throw unavailable();
        try {
            String base = properties.bridgeBaseUrl().replaceAll("/+$", "");
            URI uri = URI.create(base + "/v1/instances/" + instanceId + "/proxy?path="
                    + URLEncoder.encode(upstreamPath, StandardCharsets.UTF_8));
            HttpRequest.BodyPublisher publisher = body == null || body.length == 0
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.bridgeToken())
                    .header(HttpHeaders.ACCEPT, "*/*")
                    .method(method, publisher);
            if (body != null && body.length > 0) request.header(HttpHeaders.CONTENT_TYPE,
                    contentType == null || contentType.isBlank() ? MediaType.APPLICATION_JSON_VALUE : contentType);
            HttpResponse<InputStream> response = transport.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new ProxyResponse(response.statusCode(), response.headers().map(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (Exception exception) { throw unavailable(); }
    }

    public java.net.http.WebSocket openWebSocket(String instanceId, java.net.http.WebSocket.Listener listener) {
        if (!configured()) throw unavailable();
        try {
            String base = properties.bridgeBaseUrl().replaceAll("/+$", "")
                    .replaceFirst("^http://", "ws://").replaceFirst("^https://", "wss://");
            return transport.newWebSocketBuilder()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.bridgeToken())
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(base + "/v1/instances/" + instanceId + "/ws"), listener).join();
        } catch (Exception exception) { throw unavailable(); }
    }

    public record ProxyResponse(int status, Map<String, java.util.List<String>> headers, InputStream body) {}

    private JsonNode get(String path) {
        try { return requireClient().get().uri(path).retrieve().body(JsonNode.class); }
        catch (ApiException exception) { throw exception; }
        catch (Exception exception) {
            logFailure("GET", path, exception);
            throw unavailable();
        }
    }

    private JsonNode post(String path, Object body) {
        try {
            return requireClient().post().uri(path).contentType(MediaType.APPLICATION_JSON)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) {
            logFailure("POST", path, exception);
            throw unavailable();
        }
    }

    private void logFailure(String method, String path, Exception exception) {
        String message = exception.getMessage();
        if (message != null) {
            message = message.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer <redacted>");
            if (message.length() > 300) message = message.substring(0, 300);
        }
        log.warn("QQ Bridge call failed method={} path={} exception={} message={}",
                method, path, exception.getClass().getSimpleName(), message);
    }

    private RestClient requireClient() {
        if (!configured()) throw ApiException.unavailable("QQ_BRIDGE_UNAVAILABLE", "QQ Bridge 尚未配置或不可用");
        return client;
    }

    private ApiException unavailable() {
        return ApiException.unavailable("QQ_BRIDGE_UNAVAILABLE", "QQ Bridge 暂时不可用，请稍后重试");
    }
}
