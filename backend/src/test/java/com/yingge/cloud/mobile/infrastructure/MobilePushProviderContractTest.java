package com.yingge.cloud.mobile.infrastructure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MobilePushProviderContractTest {
    @Test
    void missingProviderFailsClosedAndNeverPretendsToDeliver() {
        MobilePushProvider provider = new NotConfiguredMobilePushProvider();
        var result = provider.send("test-registration-id",
                new MobilePushProvider.PushMessage("TASK_ASSIGNED", 1, "/tasks"));

        assertThat(provider.status()).isEqualTo(MobilePushProvider.ProviderStatus.NOT_CONFIGURED);
        assertThat(result.delivered()).isFalse();
        assertThat(result.retryable()).isFalse();
        assertThat(result.failureCode()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.providerMessageId()).isNull();
    }
}
