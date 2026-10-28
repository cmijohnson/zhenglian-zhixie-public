package com.yingge.cloud.shared.domain;

import java.text.Normalizer;
import java.util.Locale;

public final class TextNormalizer {
    private TextNormalizer() {
    }

    public static String optional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static String required(String value) {
        String normalized = optional(value);
        return normalized == null ? "" : normalized;
    }

    public static String contact(String value) {
        String normalized = optional(value);
        if (normalized == null) {
            return null;
        }
        return Normalizer.normalize(normalized, Normalizer.Form.NFKC)
                .replaceAll("[\\s\\-()（）]", "")
                .toLowerCase(Locale.ROOT);
    }

    public static String matchKey(String value) {
        String normalized = optional(value);
        if (normalized == null) {
            return null;
        }
        return Normalizer.normalize(normalized, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }
}
