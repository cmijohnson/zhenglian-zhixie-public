package com.yingge.cloud.market.domain;

public enum InteractionType {
    LEGACY,
    CUSTOMER_REPLY,
    OUTBOUND_NO_REPLY,
    AGREED_LATER,
    INTERNAL_NOTE;

    public boolean isContactAttempt() {
        return this == CUSTOMER_REPLY || this == OUTBOUND_NO_REPLY;
    }
}
