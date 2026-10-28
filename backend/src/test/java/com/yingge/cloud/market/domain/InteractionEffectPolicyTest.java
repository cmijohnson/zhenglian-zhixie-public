package com.yingge.cloud.market.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionEffectPolicyTest {
    @Test
    void internalAndAgreedLaterNeverRefreshOwnershipDeadline() {
        assertThat(InteractionEffectPolicy.decide(InteractionType.INTERNAL_NOTE,
                false, false, false, false, true).deadlineApplied()).isFalse();
        assertThat(InteractionEffectPolicy.decide(InteractionType.AGREED_LATER,
                false, false, false, false, true).validOwnershipAction()).isFalse();
    }

    @Test
    void lateDuplicateAndOutOfOrderContactsStayHistoricalOnly() {
        assertThat(InteractionEffectPolicy.decide(InteractionType.CUSTOMER_REPLY,
                true, false, false, false, true).reason()).isEqualTo("RECORDED_AFTER_48_HOURS");
        assertThat(InteractionEffectPolicy.decide(InteractionType.CUSTOMER_REPLY,
                false, true, false, false, true).reason()).isEqualTo("DUPLICATE_CONTENT");
        assertThat(InteractionEffectPolicy.decide(InteractionType.CUSTOMER_REPLY,
                false, false, true, false, true).reason()).isEqualTo("OUT_OF_ORDER_CONTACT");
    }

    @Test
    void noReplyOnlyExtendsOncePerOwnershipCycle() {
        assertThat(InteractionEffectPolicy.decide(InteractionType.OUTBOUND_NO_REPLY,
                false, false, false, false, true).deadlineApplied()).isTrue();
        assertThat(InteractionEffectPolicy.decide(InteractionType.OUTBOUND_NO_REPLY,
                false, false, false, true, true).reason()).isEqualTo("NO_REPLY_EXTENSION_ALREADY_USED");
    }

    @Test
    void contactCanConfirmTemporaryClaimWithoutAbClassification() {
        var decision = InteractionEffectPolicy.decide(InteractionType.CUSTOMER_REPLY,
                false, false, false, false, false);
        assertThat(decision.deadlineApplied()).isFalse();
        assertThat(decision.validOwnershipAction()).isTrue();
    }
}
