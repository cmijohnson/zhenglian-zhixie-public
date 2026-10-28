package com.yingge.cloud.notification.infrastructure;

import com.yingge.cloud.shared.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class WebhookSecretCipher {
    private static final byte[] AAD = "yingge-wecom-webhook-v1".getBytes(StandardCharsets.UTF_8);
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public WebhookSecretCipher(@Value("${yingge.webhook.master-key:}") String configured) {
        if (configured == null || configured.isBlank()) {
            key = null;
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configured.trim());
            if (decoded.length != 32) throw new IllegalArgumentException("key length");
            key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("YINGGE_WEBHOOK_MASTER_KEY must be Base64 for exactly 32 bytes", exception);
        }
    }

    public boolean configured() {
        return key != null;
    }

    public byte[] encrypt(String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(3 + nonce.length + ciphertext.length)
                    .put((byte) 'v').put((byte) '1').put((byte) ':').put(nonce).put(ciphertext).array();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt webhook URL", exception);
        }
    }

    public String decrypt(byte[] encoded) {
        requireKey();
        try {
            if (encoded == null || encoded.length < 32 || encoded[0] != 'v' || encoded[1] != '1' || encoded[2] != ':') {
                throw new IllegalArgumentException("unsupported payload");
            }
            ByteBuffer buffer = ByteBuffer.wrap(encoded, 3, encoded.length - 3);
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw ApiException.unavailable("WEBHOOK_DECRYPTION_FAILED", "企业微信 Webhook 无法解密，请联系管理员");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw ApiException.unavailable("WEBHOOK_ENCRYPTION_UNAVAILABLE", "服务器尚未配置企业微信 Webhook 加密主密钥");
        }
    }
}
