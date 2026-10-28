package com.yingge.cloud.assistant.infrastructure;

import com.yingge.cloud.assistant.application.AiAdminProperties;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class AiSecretCipher {
    private static final byte[] PREFIX = "yingge-ai-settings-v1".getBytes(StandardCharsets.UTF_8);
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public AiSecretCipher(AiAdminProperties properties) {
        String configured = properties.masterKey();
        if (configured == null || configured.isBlank()) {
            this.key = null;
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configured.trim());
            if (decoded.length != 32) throw new IllegalArgumentException("must decode to 32 bytes");
            this.key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("AI_SETTINGS_MASTER_KEY must be Base64 for exactly 32 bytes", exception);
        }
    }

    public boolean configured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(PREFIX);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt AI API key", exception);
        }
    }

    public String decrypt(String encoded) {
        requireKey();
        try {
            if (encoded == null || !encoded.startsWith("v1:")) throw new IllegalArgumentException("unsupported format");
            byte[] combined = Base64.getDecoder().decode(encoded.substring(3));
            if (combined.length < 29) throw new IllegalArgumentException("invalid payload");
            byte[] nonce = new byte[12];
            byte[] ciphertext = new byte[combined.length - nonce.length];
            System.arraycopy(combined, 0, nonce, 0, nonce.length);
            System.arraycopy(combined, nonce.length, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(PREFIX);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw ApiException.unavailable("AI_CONFIG_DECRYPTION_FAILED", "智能助手密钥无法解密，请由管理员重新保存");
        }
    }

    private void requireKey() {
        if (key == null) {
            throw ApiException.unavailable("AI_CONFIG_ENCRYPTION_UNAVAILABLE", "服务器未配置智能助手密钥加密主密钥");
        }
    }
}

