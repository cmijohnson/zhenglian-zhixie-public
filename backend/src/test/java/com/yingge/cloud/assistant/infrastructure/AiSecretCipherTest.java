package com.yingge.cloud.assistant.infrastructure;

import com.yingge.cloud.assistant.application.AiAdminProperties;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiSecretCipherTest {
    @Test
    void encryptsWithAuthenticatedCiphertextAndNeverEmbedsPlaintext() {
        String master = Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        var cipher = new AiSecretCipher(new AiAdminProperties(master));

        String encrypted = cipher.encrypt("provider-secret-value");

        assertThat(encrypted).startsWith("v1:").doesNotContain("provider-secret-value");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("provider-secret-value");
        assertThat(cipher.encrypt("provider-secret-value")).isNotEqualTo(encrypted);
    }

    @Test
    void refusesDatabaseKeyWritesWhenNoMasterKeyIsConfigured() {
        var cipher = new AiSecretCipher(new AiAdminProperties(""));
        assertThat(cipher.configured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("secret"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AI_CONFIG_ENCRYPTION_UNAVAILABLE"));
    }
}
