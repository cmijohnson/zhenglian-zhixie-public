package com.yingge.cloud.market.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

public final class InteractionFingerprint {
    private InteractionFingerprint() {}

    public static String create(String sourceMessageId, String channel, String summary,
                                String details, String outcome) {
        String canonical = normalized(sourceMessageId) == null
                ? String.join("\u001f", safe(channel), safe(summary), safe(details), safe(outcome))
                : "SOURCE\u001f" + normalized(sourceMessageId);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    private static String safe(String value) {
        String result = normalized(value);
        return result == null ? "" : result;
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
