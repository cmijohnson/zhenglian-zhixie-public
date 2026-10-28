package com.yingge.cloud.notification.infrastructure;

import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSecretCipherTest {
    @Test
    void encryptsWithRandomNonceAndDecryptsWithoutLeakingPlaintext() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        WebhookSecretCipher cipher = new WebhookSecretCipher(key);
        String secret = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abcdefghijklmnop1234";

        byte[] first = cipher.encrypt(secret);
        byte[] second = cipher.encrypt(secret);

        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain("qyapi", "abcdefghijklmnop1234");
        assertThat(cipher.decrypt(first)).isEqualTo(secret);
        assertThat(cipher.decrypt(second)).isEqualTo(secret);
    }

    @Test
    void failsClosedWhenNoMasterKeyIsConfigured() {
        WebhookSecretCipher cipher = new WebhookSecretCipher("");
        assertThat(cipher.configured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("secret"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未配置");
    }
}
