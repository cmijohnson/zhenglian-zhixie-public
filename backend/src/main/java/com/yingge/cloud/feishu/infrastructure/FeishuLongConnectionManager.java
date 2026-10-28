package com.yingge.cloud.feishu.infrastructure;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.service.drive.DriveService;
import com.lark.oapi.service.drive.v1.model.BitableTableRecordAction;
import com.lark.oapi.service.drive.v1.model.P2FileBitableRecordChangedV1;
import com.lark.oapi.service.drive.v1.model.P2FileBitableRecordChangedV1Data;
import com.lark.oapi.service.drive.v1.model.UserId;
import com.yingge.cloud.feishu.application.FeishuEventInboxService;
import com.yingge.cloud.feishu.application.FeishuDealBotEventService;
import com.yingge.cloud.feishu.application.FeishuIntegrationService;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "app.feishu.long-connection-enabled", havingValue = "true")
public class FeishuLongConnectionManager {
    private static final Duration CONNECTING_WATCHDOG = Duration.ofMinutes(2);
    private static final Duration SUBSCRIPTION_RETRY_BACKOFF = Duration.ofMinutes(5);

    private final FeishuIntegrationService integrations;
    private final FeishuEventInboxService inbox;
    private final FeishuBitableClient bitableClient;
    private final FeishuDealBotEventService dealBot;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Connection> clients = new HashMap<>();
    private final Map<UUID, Instant> subscriptionRetryAfter = new HashMap<>();

    public FeishuLongConnectionManager(FeishuIntegrationService integrations, FeishuEventInboxService inbox,
                                       FeishuBitableClient bitableClient, FeishuDealBotEventService dealBot) {
        this.integrations = integrations;
        this.inbox = inbox;
        this.bitableClient = bitableClient;
        this.dealBot = dealBot;
    }

    @Scheduled(initialDelayString = "${app.feishu.inbound-run-delay-ms:5000}", fixedDelay = 30000)
    public synchronized void refresh() {
        Set<UUID> desired = new HashSet<>();
        for (FeishuIntegrationService.IntegrationRow row : integrations.longConnectionIntegrations()) {
            desired.add(row.id());
            Connection current = clients.get(row.id());
            Instant retryAfter = subscriptionRetryAfter.get(row.id());
            if (current == null && retryAfter != null && retryAfter.isAfter(Instant.now())) continue;
            if (current != null && isStuckConnecting(row, Instant.now())) {
                current.client().close();
                clients.remove(row.id());
                integrations.updateEventState(row.id(), "DEGRADED", "FEISHU_LONG_CONNECTION_STALLED", false);
                start(row);
                continue;
            }
            if (current == null || current.version() != row.version()) {
                if (current != null) current.client().close();
                start(row);
            }
        }
        clients.entrySet().removeIf(entry -> {
            if (desired.contains(entry.getKey())) return false;
            entry.getValue().client().close();
            subscriptionRetryAfter.remove(entry.getKey());
            integrations.updateEventState(entry.getKey(), "DISABLED", null, false);
            return true;
        });
    }

    static boolean isStuckConnecting(FeishuIntegrationService.IntegrationRow row, Instant now) {
        return "CONNECTING".equals(row.eventState())
                && row.updatedAt().isBefore(now.minus(CONNECTING_WATCHDOG));
    }

    static boolean requiresOwnerSubscriptionAuthorization(FeishuIntegrationService.IntegrationRow row) {
        return row.eventSubscriptionAuthorizedAt() == null;
    }

    private void start(FeishuIntegrationService.IntegrationRow row) {
        FeishuBitableClient.Credentials credentials = integrations.credentialsFor(row);
        if (row.inboundSyncEnabled() && requiresOwnerSubscriptionAuthorization(row)) {
            try {
                bitableClient.ensureRecordChangeSubscription(credentials, row.baseAppToken());
                subscriptionRetryAfter.remove(row.id());
            } catch (FeishuBitableClient.FeishuClientException exception) {
                subscriptionRetryAfter.put(row.id(), Instant.now().plus(SUBSCRIPTION_RETRY_BACKOFF));
                integrations.updateEventState(row.id(), "DEGRADED", exception.code(), false);
                return;
            } catch (RuntimeException exception) {
                subscriptionRetryAfter.put(row.id(), Instant.now().plus(SUBSCRIPTION_RETRY_BACKOFF));
                integrations.updateEventState(row.id(), "DEGRADED", FeishuBitableClient.safeFailureCode(exception), false);
                return;
            }
        }
        subscriptionRetryAfter.remove(row.id());
        EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                .onP2FileBitableRecordChangedV1(new DriveService.P2FileBitableRecordChangedV1Handler() {
                    @Override
                    public void handle(P2FileBitableRecordChangedV1 event) {
                        receive(row.id(), event);
                    }
                })
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        dealBot.receiveMessage(row.id(), event);
                    }
                })
                .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                    @Override
                    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) {
                        return dealBot.receiveCard(row.id(), event);
                    }
                }).build();
        AtomicReference<Connection> connectionRef = new AtomicReference<>();
        com.lark.oapi.ws.Client sdkClient = new com.lark.oapi.ws.Client.Builder(
                credentials.appId(), credentials.appSecret())
                .eventHandler(dispatcher)
                .autoReconnect(true)
                .onReconnecting(() -> updateIfCurrent(row.id(), connectionRef.get(), "CONNECTING", null))
                .onReconnected(() -> updateIfCurrent(row.id(), connectionRef.get(), "CONNECTED", null))
                .build();
        Connection connection = new Connection(row.version(), sdkClient);
        connectionRef.set(connection);
        clients.put(row.id(), connection);
        integrations.updateEventState(row.id(), "CONNECTING", null, false);
        executor.submit(() -> {
            try {
                sdkClient.start();
                sdkClient.awaitReady(10000);
                updateIfCurrent(row.id(), connection, "CONNECTED", null);
            } catch (Exception exception) {
                synchronized (this) {
                    if (clients.get(row.id()) == connection) {
                        clients.remove(row.id());
                        sdkClient.close();
                        integrations.updateEventState(row.id(), "DEGRADED", "FEISHU_LONG_CONNECTION_FAILED", false);
                    }
                }
            }
        });
    }

    private synchronized void updateIfCurrent(UUID integrationId, Connection expected,
                                              String state, String errorCode) {
        if (expected != null && clients.get(integrationId) == expected) {
            integrations.updateEventState(integrationId, state, errorCode, false);
        }
    }

    private void receive(UUID integrationId, P2FileBitableRecordChangedV1 envelope) {
        if (envelope == null || envelope.getHeader() == null || envelope.getEvent() == null) return;
        P2FileBitableRecordChangedV1Data data = envelope.getEvent();
        UserId operator = data.getOperatorId();
        BitableTableRecordAction[] actions = data.getActionList();
        if (actions == null) return;
        for (BitableTableRecordAction action : actions) {
            inbox.receive(integrationId, new FeishuEventInboxService.InboundEvent(
                    envelope.getHeader().getEventId(), envelope.getHeader().getEventType(),
                    envelope.getHeader().getAppId(), envelope.getHeader().getTenantKey(),
                    data.getFileToken(), data.getTableId(), action.getRecordId(), action.getAction(),
                    operator == null ? null : operator.getUserId(), operator == null ? null : operator.getOpenId()));
        }
    }

    @PreDestroy
    public synchronized void close() {
        clients.values().forEach(connection -> connection.client().close());
        clients.clear();
        subscriptionRetryAfter.clear();
        executor.shutdownNow();
    }

    private record Connection(long version, com.lark.oapi.ws.Client client) {}
}
