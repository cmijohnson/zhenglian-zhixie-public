package com.yingge.cloud.feishu.infrastructure;

import com.yingge.cloud.feishu.application.FeishuIntegrationService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FeishuLongConnectionManagerTest {
    @Test
    void restartsAConnectionThatRemainsConnectingPastTheWatchdog() {
        Instant now = Instant.parse("2026-09-04T04:00:00Z");

        assertThat(FeishuLongConnectionManager.isStuckConnecting(row("CONNECTING", now.minusSeconds(121)), now))
                .isTrue();
        assertThat(FeishuLongConnectionManager.isStuckConnecting(row("CONNECTING", now.minusSeconds(119)), now))
                .isFalse();
        assertThat(FeishuLongConnectionManager.isStuckConnecting(row("CONNECTED", now.minusSeconds(300)), now))
                .isFalse();
    }

    @Test
    void skipsTenantTokenSubscriptionProbeAfterOwnerAuthorization() {
        Instant now = Instant.parse("2026-09-04T04:00:00Z");

        assertThat(FeishuLongConnectionManager.requiresOwnerSubscriptionAuthorization(
                row("CONNECTING", now, null))).isTrue();
        assertThat(FeishuLongConnectionManager.requiresOwnerSubscriptionAuthorization(
                row("CONNECTING", now, now.minusSeconds(30)))).isFalse();
    }

    private FeishuIntegrationService.IntegrationRow row(String state, Instant updatedAt) {
        return row(state, updatedAt, null);
    }

    private FeishuIntegrationService.IntegrationRow row(String state, Instant updatedAt,
                                                         Instant subscriptionAuthorizedAt) {
        return new FeishuIntegrationService.IntegrationRow(
                UUID.randomUUID(), UUID.randomUUID(), "app", new byte[]{1}, "base", "table", "name",
                true, false, true, "READY", null, state, null, null, subscriptionAuthorizedAt,
                null, null, null, null, 1,
                updatedAt.minusSeconds(30), updatedAt);
    }
}
