package com.yingge.cloud.qq.infrastructure;

import com.yingge.cloud.qq.application.QqProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QqDataCipherTest {
    @Test
    void encryptsWithRandomNonceAndRejectsTampering() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        var cipher = new QqDataCipher(new QqProperties("http://bridge", "token", key, "/tmp/qq", 30, 10000));
        byte[] plaintext = "private qq conversation".getBytes(StandardCharsets.UTF_8);

        byte[] first = cipher.encrypt(plaintext);
        byte[] second = cipher.encrypt(plaintext);
        assertThat(first).isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo(plaintext);
        String opaque = cipher.encryptOpaque("member-bound-media-ticket");
        assertThat(opaque).doesNotContain("/").doesNotContain("+").doesNotContain("=");
        assertThat(cipher.decryptOpaque(opaque)).isEqualTo("member-bound-media-ticket");

        first[first.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(first)).hasMessageContaining("无法解密");
    }

    @Test
    void requiresExactlyThirtyTwoDecodedKeyBytes() {
        assertThatThrownBy(() -> new QqDataCipher(new QqProperties("", "", "c2hvcnQ=", "/tmp", 30, 10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exactly 32 bytes");
    }
}
