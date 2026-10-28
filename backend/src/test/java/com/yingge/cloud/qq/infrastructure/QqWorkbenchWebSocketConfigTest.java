package com.yingge.cloud.qq.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.net.http.WebSocket;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class QqWorkbenchWebSocketConfigTest {
    @Test
    void validatesTheCompleteBrowserAuthorityIncludingNonDefaultUatPorts() {
        assertThat(QqWorkbenchWebSocketConfig.QqWorkbenchHandshakeInterceptor
                .sameAuthority("http://127.0.0.1:18084", "127.0.0.1:18084")).isTrue();
        assertThat(QqWorkbenchWebSocketConfig.QqWorkbenchHandshakeInterceptor
                .sameAuthority("http://127.0.0.1:18084", "127.0.0.1")).isFalse();
        assertThat(QqWorkbenchWebSocketConfig.QqWorkbenchHandshakeInterceptor
                .sameAuthority("https://example.test", "example.test")).isTrue();
        assertThat(QqWorkbenchWebSocketConfig.QqWorkbenchHandshakeInterceptor
                .sameAuthority("https://evil.test", "example.test")).isFalse();
    }

    @Test
    void rejectsOversizedClientFramesBeforeTheyReachQce() throws Exception {
        var handler = new QqWorkbenchWebSocketConfig.QqWorkbenchWebSocketHandler(null, null);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.isOpen()).thenReturn(true);

        handler.handleMessage(session, new BinaryMessage(new byte[8 * 1024 * 1024 + 1]));

        verify(session).close(CloseStatus.TOO_BIG_TO_PROCESS);
    }

    @Test
    void closesTheUpstreamSocketWhenTheBrowserProvidesNoCloseReason() {
        QqBridgeClient bridge = mock(QqBridgeClient.class);
        WebSocket target = mock(WebSocket.class);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("session-1");
        when(session.getAttributes()).thenReturn(Map.of("qqInstanceId", "instance-1"));
        when(bridge.openWebSocket(any(), any())).thenReturn(target);
        var handler = new QqWorkbenchWebSocketConfig.QqWorkbenchWebSocketHandler(bridge, null);
        handler.afterConnectionEstablished(session);

        assertThatCode(() -> handler.afterConnectionClosed(session, CloseStatus.NORMAL)).doesNotThrowAnyException();
        verify(target).sendClose(CloseStatus.NORMAL.getCode(), "");
    }
}
