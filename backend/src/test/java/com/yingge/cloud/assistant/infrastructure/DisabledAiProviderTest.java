package com.yingge.cloud.assistant.infrastructure;

import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledAiProviderTest {
    @Test
    void reportsExplicitDegradedModeWithoutAffectingOtherModules() {
        var provider = new DisabledAiProvider();
        assertThat(provider.available()).isFalse();
        assertThatThrownBy(() -> provider.generate("system", "prompt", false))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("AI_PROVIDER_UNAVAILABLE"));
    }
}

