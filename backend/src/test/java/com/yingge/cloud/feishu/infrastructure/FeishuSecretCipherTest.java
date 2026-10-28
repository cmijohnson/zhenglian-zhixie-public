package com.yingge.cloud.feishu.infrastructure;

import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuSecretCipherTest {
    @Test
    void encryptsAppSecretWithRandomNonce() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        FeishuSecretCipher cipher = new FeishuSecretCipher(key);
        String secret = "feishu-app-secret-value";

        byte[] first = cipher.encrypt(secret);
        byte[] second = cipher.encrypt(secret);

        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, StandardCharsets.UTF_8)).doesNotContain(secret);
        assertThat(cipher.decrypt(first)).isEqualTo(secret);
        assertThat(cipher.decrypt(second)).isEqualTo(secret);
    }

    @Test
    void failsClosedWithoutMasterKey() {
        FeishuSecretCipher cipher = new FeishuSecretCipher("");
        assertThat(cipher.configured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("secret"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("未配置");
    }
}
