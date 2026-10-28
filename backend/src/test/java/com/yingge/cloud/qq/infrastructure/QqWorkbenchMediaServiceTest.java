package com.yingge.cloud.qq.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.qq.application.QqProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QqWorkbenchMediaServiceTest {
    private final QqWorkbenchMediaService media = new QqWorkbenchMediaService(new ObjectMapper(), cipher());

    @Test
    void replacesTrustedMessageMediaWithMemberBoundOpaqueUrl() {
        byte[] rewritten = media.rewriteJson("""
                {"data":{"messages":[{"elements":[{"picElement":{
                  "originImageUrl":"https://multimedia.nt.qq.com.cn/image.jpg",
                  "sourcePath":"/data/private/image.jpg"}}]}]}}
                """.getBytes(StandardCharsets.UTF_8), UUID.fromString("00000000-0000-0000-0000-000000000123"), "instance-1");
        String json = new String(rewritten, StandardCharsets.UTF_8);
        assertThat(json).contains("/api/v1/qq-workbench/media/");
        assertThat(json).doesNotContain("multimedia.nt.qq.com.cn");
        assertThat(json).doesNotContain("/data/private/image.jpg");
    }

    @Test
    void refusesUntrustedMediaHostsInsteadOfCreatingAnSsrfPrimitive() {
        assertThatThrownBy(() -> media.rewriteJson("""
                {"originImageUrl":"http://127.0.0.1:8080/admin"}
                """.getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), "instance-1"))
                .hasMessageContaining("来源不受信任");
    }

    @Test
    void rewritesAvatarUrlsAndRejectsOversizedRealtimePayloads() {
        byte[] rewritten = media.rewriteJson("""
                {"avatarUrl":"https://q1.qlogo.cn/g?b=qq&nk=123456&s=100"}
                """.getBytes(StandardCharsets.UTF_8), UUID.randomUUID(), "instance-1");
        assertThat(new String(rewritten, StandardCharsets.UTF_8))
                .contains("/api/v1/qq-workbench/media/")
                .doesNotContain("qlogo.cn");

        assertThatThrownBy(() -> media.rewriteWebSocketText("x".repeat(8 * 1024 * 1024 + 1),
                UUID.randomUUID(), "instance-1"))
                .hasMessageContaining("实时消息不能超过 8 MB");
    }

    @Test
    void acceptsSafeExporterMimeTypesButRejectsExecutableContent() {
        assertThat(QqWorkbenchMediaService.isAllowedQceContentType("application/json")).isTrue();
        assertThat(QqWorkbenchMediaService.isAllowedQceContentType("text/html")).isTrue();
        assertThat(QqWorkbenchMediaService.isAllowedQceContentType("application/zip")).isTrue();
        assertThat(QqWorkbenchMediaService.isAllowedQceContentType("application/javascript")).isFalse();
    }

    private QqDataCipher cipher() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new QqDataCipher(new QqProperties("", "", key, "/tmp", 30, 100));
    }
}
