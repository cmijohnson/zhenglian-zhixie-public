package com.yingge.cloud.qq.infrastructure;

import com.yingge.cloud.qq.application.QqProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class QqBridgeClientContractTest {
    @Test
    void pinsInternalBridgeTransportToHttp11() {
        assertThat(QqBridgeClient.newBridgeTransport().version())
                .isEqualTo(HttpClient.Version.HTTP_1_1);
    }

    @Test
    void createsIsolatedInstanceWithServerSideBearerToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new QqBridgeClient(new QqProperties("http://qq-bridge:40700", "internal-secret",
                "", "/tmp", 30, 10000), builder, false);
        server.expect(requestTo("http://qq-bridge:40700/v1/instances"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer internal-secret"))
                .andExpect(content().json("{\"key\":\"organization:membership\"}"))
                .andRespond(withStatus(HttpStatus.CREATED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"instanceId\":\"0123456789abcdef01234567\"}"));

        assertThat(client.create("organization:membership").path("instanceId").asText())
                .isEqualTo("0123456789abcdef01234567");
        server.verify();
    }

    @Test
    void sendsInternalBearerTokenAndRestrictedPrivateMessagePayload() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var client = new QqBridgeClient(new QqProperties("http://qq-bridge:40700", "internal-secret",
                "", "/tmp", 30, 10000), builder, false);
        long start = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        long end = Instant.parse("2026-08-02T00:00:00Z").toEpochMilli();
        server.expect(requestTo("http://qq-bridge:40700/v1/instances/0123456789abcdef01234567/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer internal-secret"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"chatType":2,"peerUid":"friend-uid","startTime":1785542400000,"endTime":1785628800000,"page":1,"limit":200}
                        """))
                .andRespond(withSuccess("{\"data\":{\"messages\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(client.messages("0123456789abcdef01234567", 2, "friend-uid", start, end, 1, 200)
                .path("data").path("messages").isArray()).isTrue();
        server.verify();
    }
}
