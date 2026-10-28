package com.yingge.cloud.market.domain;

import com.yingge.cloud.interaction.api.InteractionDtos;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionFingerprintTest {
    @Test
    void normalizesWhitespaceWidthAndCaseForDuplicateDetection() {
        String first = InteractionFingerprint.create(null, "QQ", "  已回复  ", "ＡＢＣ\n下一步", "OK");
        String second = InteractionFingerprint.create(null, "qq", "已回复", "abc 下一步", "ok");

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void stableSourceMessageIdTakesPrecedenceOverEditedSummary() {
        String first = InteractionFingerprint.create("msg-2026-001", "QQ", "原摘要", null, null);
        String second = InteractionFingerprint.create(" MSG-2026-001 ", "微信", "修改后的摘要", "补充", "结果");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void omittedInteractionTypeDefaultsToNonRefreshingInternalNote() {
        InteractionDtos.CreateRequest request = new InteractionDtos.CreateRequest(
                Instant.EPOCH, "QQ", "摘要", null, null, null, null,
                null, null, null, Set.of());

        assertThat(request.effectiveType()).isEqualTo(InteractionType.INTERNAL_NOTE);
        assertThat(request.shouldUpdateTags()).isFalse();
    }

    @Test
    void tagUpdateUsesExplicitFlagAndSafeLegacyFallback() {
        InteractionDtos.CreateRequest legacyNonEmpty = new InteractionDtos.CreateRequest(
                Instant.EPOCH, "QQ", "摘要", null, null, null, null,
                null, null, null, Set.of(UUID.randomUUID()));
        InteractionDtos.CreateRequest explicitClear = new InteractionDtos.CreateRequest(
                Instant.EPOCH, "QQ", "摘要", null, null, null, null,
                null, null, null, Set.of(), null, null, true);

        assertThat(legacyNonEmpty.shouldUpdateTags()).isTrue();
        assertThat(explicitClear.shouldUpdateTags()).isTrue();
    }
}
