package com.yingge.cloud.mobile.infrastructure;

import org.springframework.stereotype.Component;

/** Fail-closed provider used until production UniPush credentials and an implementation are installed. */
@Component
public class NotConfiguredMobilePushProvider implements MobilePushProvider {
    @Override
    public ProviderStatus status() {
        return ProviderStatus.NOT_CONFIGURED;
    }

    @Override
    public DeliveryResult send(String registrationId, PushMessage message) {
        return DeliveryResult.failed("NOT_CONFIGURED", false);
    }
}
