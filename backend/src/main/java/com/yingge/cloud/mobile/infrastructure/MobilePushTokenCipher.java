package com.yingge.cloud.mobile.infrastructure;

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
public class MobilePushTokenCipher {
    private static final byte FORMAT_VERSION = 1;
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public MobilePushTokenCipher(@Value("${app.mobile.push-encryption-key:}") String encodedKey) {
        this.key = decode(encodedKey);
    }

    public byte[] encrypt(String value) {
        if (key == null) {
            throw ApiException.unavailable("MOBILE_PUSH_NOT_CONFIGURED", "移动推送尚未完成服务器安全配置");
        }
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + iv.length + ciphertext.length)
                    .put(FORMAT_VERSION).put(iv).put(ciphertext).array();
        } catch (Exception exception) {
            throw ApiException.unavailable("MOBILE_PUSH_ENCRYPTION_FAILED", "移动推送凭据无法安全保存");
        }
    }

    public String decrypt(byte[] value) {
        if (key == null) {
            throw ApiException.unavailable("MOBILE_PUSH_NOT_CONFIGURED", "移动推送尚未完成服务器安全配置");
        }
        try {
            ByteBuffer buffer = ByteBuffer.wrap(value);
            if (buffer.get() != FORMAT_VERSION) throw new IllegalArgumentException("unsupported ciphertext");
            byte[] iv = new byte[12];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw ApiException.unavailable("MOBILE_PUSH_DECRYPTION_FAILED", "移动推送凭据无法安全读取");
        }
    }

    public boolean configured() {
        return key != null;
    }

    private byte[] decode(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded.trim());
            if (bytes.length != 32) throw new IllegalArgumentException();
            return bytes;
        } catch (RuntimeException exception) {
            throw new IllegalStateException("MOBILE_PUSH_ENCRYPTION_KEY must be a base64 encoded 32-byte key");
        }
    }
}
