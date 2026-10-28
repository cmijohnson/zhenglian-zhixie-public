package com.yingge.cloud.mobile.infrastructure;

/** Provider boundary for the native push service. Implementations must never log registration ids. */
public interface MobilePushProvider {
    ProviderStatus status();

    DeliveryResult send(String registrationId, PushMessage message);

    enum ProviderStatus { READY, NOT_CONFIGURED }

    record PushMessage(String eventType, int count, String deeplink) {}

    record DeliveryResult(boolean delivered, boolean retryable, String providerMessageId, String failureCode) {
        public static DeliveryResult delivered(String providerMessageId) {
            return new DeliveryResult(true, false, providerMessageId, null);
        }

        public static DeliveryResult failed(String failureCode, boolean retryable) {
            return new DeliveryResult(false, retryable, null, failureCode);
        }
    }
}
