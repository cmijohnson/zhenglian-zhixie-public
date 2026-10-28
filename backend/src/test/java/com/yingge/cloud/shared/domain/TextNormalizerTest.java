package com.yingge.cloud.shared.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextNormalizerTest {
    @Test
    void normalizesContactCharactersForDuplicateDetection() {
        assertThat(TextNormalizer.contact(" １３８-0013（8000） ")).isEqualTo("13800138000");
        assertThat(TextNormalizer.contact(" YingGe-WeChat ")).isEqualTo("yinggewechat");
    }

    @Test
    void returnsNullForMissingOptionalValues() {
        assertThat(TextNormalizer.optional("  ")).isNull();
        assertThat(TextNormalizer.contact(null)).isNull();
    }
}

