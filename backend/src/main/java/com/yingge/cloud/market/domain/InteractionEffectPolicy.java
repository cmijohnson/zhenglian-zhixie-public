package com.yingge.cloud.market.domain;

public final class InteractionEffectPolicy {
    private InteractionEffectPolicy() {}

    public static Decision decide(InteractionType type, boolean recordedAfter48Hours,
                                  boolean duplicate, boolean outOfOrder,
                                  boolean noReplyExtensionUsed, boolean hasAbClassification) {
        if (!type.isContactAttempt()) return Decision.ignored("NON_EFFECTIVE_INTERACTION_TYPE", false);
        if (recordedAfter48Hours) return Decision.ignored("RECORDED_AFTER_48_HOURS", false);
        if (duplicate) return Decision.ignored("DUPLICATE_CONTENT", false);
        if (outOfOrder) return Decision.ignored("OUT_OF_ORDER_CONTACT", false);
        if (type == InteractionType.OUTBOUND_NO_REPLY && noReplyExtensionUsed) {
            return Decision.ignored("NO_REPLY_EXTENSION_ALREADY_USED", false);
        }
        if (!hasAbClassification) return Decision.ignored("NO_AB_CLASSIFICATION", true);
        return new Decision(true, true, "EFFECTIVE_CONTACT");
    }

    public record Decision(boolean deadlineApplied, boolean validOwnershipAction, String reason) {
        private static Decision ignored(String reason, boolean validOwnershipAction) {
            return new Decision(false, validOwnershipAction, reason);
        }
    }
}
