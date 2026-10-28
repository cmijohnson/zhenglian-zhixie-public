package com.yingge.cloud.qq.infrastructure;

import com.yingge.cloud.qq.application.QqProperties;
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
public class QqDataCipher {
    private static final byte[] AAD = "yingge-qq-import-v1".getBytes(StandardCharsets.UTF_8);
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public QqDataCipher(QqProperties properties) {
        String configured = properties.masterKey();
        if (configured == null || configured.isBlank()) {
            key = null;
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(configured.trim());
            if (decoded.length != 32) throw new IllegalArgumentException("key length");
            key = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("QQ_DATA_MASTER_KEY must be Base64 for exactly 32 bytes", exception);
        }
    }

    public boolean configured() { return key != null; }

    public byte[] encrypt(byte[] plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(AAD);
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(3 + nonce.length + ciphertext.length)
                    .put((byte) 'v').put((byte) '1').put((byte) ':').put(nonce).put(ciphertext).array();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to encrypt QQ data", exception);
        }
    }

    public byte[] decrypt(byte[] encoded) {
        requireKey();
        try {
            if (encoded.length < 32 || encoded[0] != 'v' || encoded[1] != '1' || encoded[2] != ':') {
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
            return cipher.doFinal(ciphertext);
        } catch (Exception exception) {
            throw ApiException.unavailable("QQ_DATA_DECRYPTION_FAILED", "QQ 对话原文无法解密，请联系管理员");
        }
    }

    public String encryptText(String value) {
        return Base64.getEncoder().encodeToString(encrypt(value.getBytes(StandardCharsets.UTF_8)));
    }

    public String encryptOpaque(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypt(value.getBytes(StandardCharsets.UTF_8)));
    }

    public String decryptOpaque(String value) {
        try {
            return new String(decrypt(Base64.getUrlDecoder().decode(value)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("QQ_MEDIA_TOKEN_INVALID", "QQ 媒体凭据无效");
        }
    }

    private void requireKey() {
        if (key == null) throw ApiException.unavailable("QQ_ENCRYPTION_UNAVAILABLE", "服务器尚未配置 QQ 对话加密主密钥");
    }
}
