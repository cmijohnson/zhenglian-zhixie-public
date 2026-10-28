package com.yingge.cloud.qq.infrastructure;

import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.qq.application.QqIntegrationService;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@EnableWebSocket
public class QqWorkbenchWebSocketConfig implements WebSocketConfigurer {
    private final QqWorkbenchWebSocketHandler handler;
    private final QqWorkbenchHandshakeInterceptor interceptor;

    public QqWorkbenchWebSocketConfig(QqBridgeClient bridge, QqIntegrationService service,
                                      QqWorkbenchMediaService media) {
        this.handler = new QqWorkbenchWebSocketHandler(bridge, media);
        this.interceptor = new QqWorkbenchHandshakeInterceptor(service);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/qq-workbench/ws").addInterceptors(interceptor);
    }

    static final class QqWorkbenchHandshakeInterceptor implements HandshakeInterceptor {
        private final QqIntegrationService service;
        QqWorkbenchHandshakeInterceptor(QqIntegrationService service) { this.service = service; }

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                       WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (!(request.getPrincipal() instanceof Authentication authentication)
                    || !(authentication.getPrincipal() instanceof CloudPrincipal principal)) return false;
            String origin = request.getHeaders().getOrigin();
            String host = request.getHeaders().getFirst(HttpHeaders.HOST);
            if (origin == null || host == null || !sameAuthority(origin, host)) return false;
            var session = service.requireWorkbenchSession(principal.actor());
            attributes.put("qqInstanceId", session.instanceId());
            attributes.put("qqMembershipId", session.membershipId().toString());
            return true;
        }

        static boolean sameAuthority(String origin, String host) {
            try { return URI.create(origin).getRawAuthority().equalsIgnoreCase(host); }
            catch (Exception exception) { return false; }
        }

        @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                             WebSocketHandler wsHandler, Exception exception) { }
    }

    static final class QqWorkbenchWebSocketHandler implements WebSocketHandler {
        private static final int MAX_WEBSOCKET_MESSAGE_BYTES = 8 * 1024 * 1024;
        private final QqBridgeClient bridge;
        private final QqWorkbenchMediaService media;
        private final Map<String, WebSocket> upstream = new ConcurrentHashMap<>();

        QqWorkbenchWebSocketHandler(QqBridgeClient bridge, QqWorkbenchMediaService media) {
            this.bridge = bridge;
            this.media = media;
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            String instanceId = (String) session.getAttributes().get("qqInstanceId");
            upstream.put(session.getId(), bridge.openWebSocket(instanceId, new RelayListener(session)));
        }

        @Override
        public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
            if (message.getPayloadLength() > MAX_WEBSOCKET_MESSAGE_BYTES) {
                close(session, CloseStatus.TOO_BIG_TO_PROCESS);
                return;
            }
            WebSocket target = upstream.get(session.getId());
            if (target == null) return;
            if (message instanceof TextMessage text) target.sendText(text.getPayload(), true);
            else if (message instanceof BinaryMessage binary) target.sendBinary(binary.getPayload(), true);
        }

        @Override public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
            close(session, CloseStatus.SERVER_ERROR);
        }
        @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
            WebSocket target = upstream.remove(session.getId());
            if (target != null) target.sendClose(closeStatus.getCode(), safeCloseReason(closeStatus));
        }
        @Override public boolean supportsPartialMessages() { return false; }

        static String safeCloseReason(CloseStatus status) {
            return status.getReason() == null ? "" : status.getReason();
        }

        private void close(WebSocketSession session, CloseStatus status) throws Exception {
            WebSocket target = upstream.remove(session.getId());
            if (target != null) target.abort();
            if (session.isOpen()) session.close(status);
        }

        final class RelayListener implements WebSocket.Listener {
            private final WebSocketSession session;
            private final StringBuilder text = new StringBuilder();
            private final ByteArrayOutputStream binary = new ByteArrayOutputStream();
            private int textBytes;
            RelayListener(WebSocketSession session) { this.session = session; }

            @Override public void onOpen(WebSocket socket) { socket.request(1); }
            @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                textBytes += data.toString().getBytes(StandardCharsets.UTF_8).length;
                if (textBytes > MAX_WEBSOCKET_MESSAGE_BYTES) {
                    socket.abort();
                    try { close(session, CloseStatus.TOO_BIG_TO_PROCESS); } catch (Exception ignored) { }
                    return null;
                }
                text.append(data);
                if (last) {
                    UUID membershipId = UUID.fromString((String) session.getAttributes().get("qqMembershipId"));
                    String instanceId = (String) session.getAttributes().get("qqInstanceId");
                    send(new TextMessage(media.rewriteWebSocketText(text.toString(), membershipId, instanceId)));
                    text.setLength(0);
                    textBytes = 0;
                }
                socket.request(1); return null;
            }
            @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
                if (binary.size() + data.remaining() > MAX_WEBSOCKET_MESSAGE_BYTES) {
                    socket.abort();
                    try { close(session, CloseStatus.TOO_BIG_TO_PROCESS); } catch (Exception ignored) { }
                    return null;
                }
                byte[] bytes = new byte[data.remaining()]; data.get(bytes); binary.writeBytes(bytes);
                if (last) { send(new BinaryMessage(binary.toByteArray())); binary.reset(); }
                socket.request(1); return null;
            }
            @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
                try { if (session.isOpen()) session.close(new CloseStatus(statusCode, reason)); }
                catch (Exception ignored) { }
                upstream.remove(session.getId()); return null;
            }
            @Override public void onError(WebSocket socket, Throwable error) {
                try { close(session, CloseStatus.SERVER_ERROR); }
                catch (Exception ignored) { }
            }
            private void send(WebSocketMessage<?> message) {
                try { synchronized (session) { if (session.isOpen()) session.sendMessage(message); } }
                catch (Exception exception) { try { close(session, CloseStatus.SERVER_ERROR); } catch (Exception ignored) { } }
            }
        }
    }
}
