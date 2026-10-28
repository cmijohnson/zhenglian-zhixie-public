package com.yingge.cloud.feishu.application;

import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class FeishuSyncWorker {
    private final JdbcTemplate jdbc;
    private final FeishuIntegrationService integrations;
    private final FeishuCustomerSnapshotRepository snapshots;
    private final FeishuBitableClient client;
    private final TransactionTemplate transactions;

    public FeishuSyncWorker(JdbcTemplate jdbc, FeishuIntegrationService integrations,
                            FeishuCustomerSnapshotRepository snapshots, FeishuBitableClient client,
                            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.integrations = integrations;
        this.snapshots = snapshots;
        this.client = client;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Scheduled(cron = "0 5 * * * *", zone = "Asia/Shanghai")
    public void scheduleHourlyRuns() {
        for (FeishuIntegrationService.IntegrationRow integration : integrations.activeIntegrations()) {
            transactions.executeWithoutResult(status -> integrations.createHourlyRun(integration));
        }
        processRequestedRuns();
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Shanghai")
    public void scheduleNightlyReconciliation() {
        for (FeishuIntegrationService.IntegrationRow integration : integrations.activeIntegrations()) {
            transactions.executeWithoutResult(status -> integrations.createSystemFullRun(integration, "RECONCILIATION"));
        }
    }

    @Scheduled(fixedDelayString = "${app.feishu.run-delay-ms:60000}")
    public void processRequestedRuns() {
        List<UUID> runIds = jdbc.queryForList("""
                select id from feishu_bitable_sync_runs
                where status in ('PENDING','RUNNING') order by created_at limit 10
                """, UUID.class);
        for (UUID runId : runIds) processRun(runId);
    }

    void processRun(UUID runId) {
        RunRow run = jdbc.query("""
                select id,integration_id,organization_id,run_type,status,total_count
                from feishu_bitable_sync_runs where id=? and status in ('PENDING','RUNNING')
                """, (rs, index) -> new RunRow(rs.getObject("id", UUID.class),
                rs.getObject("integration_id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("run_type"), rs.getString("status"), rs.getInt("total_count")), runId)
                .stream().findFirst().orElse(null);
        if (run == null) return;
        jdbc.update("""
                update feishu_bitable_sync_runs set status='RUNNING',started_at=coalesce(started_at,now()),updated_at=now()
                where id=? and status in ('PENDING','RUNNING')
                """, runId);
        FeishuIntegrationService.RemoteContext remote;
        try {
            remote = integrations.requireRemoteContext(run.integrationId());
        } catch (RuntimeException exception) {
            failRun(runId, integrations.safeError(exception));
            return;
        }
        while (true) {
            CanaryState canary = canaryState(run);
            if (canary.failed()) {
                failRun(runId, "FEISHU_INITIAL_CANARY_FAILED");
                return;
            }
            int limit = canary.active() ? Math.max(1, canary.target() - canary.completed()) : 100;
            List<QueueItem> batch = transactions.execute(status -> claimBatch(runId, limit, canary.active()));
            if (batch == null || batch.isEmpty()) break;
            deliverBatch(run, remote, batch);
        }
        finishRunIfDone(runId, run.integrationId());
    }

    List<QueueItem> claimBatch(UUID runId, int limit, boolean canaryOnly) {
        jdbc.update("""
                update feishu_bitable_sync_queue set status='RETRYING',next_attempt_at=now(),
                       last_error_code='WORKER_LEASE_EXPIRED',updated_at=now()
                where run_id=? and status='SENDING' and updated_at<now()-interval '5 minutes'
                """, runId);
        String canaryFilter = canaryOnly ? " and id in (select candidate.id from feishu_bitable_sync_queue candidate where candidate.run_id=? order by candidate.created_at limit 10)" : "";
        List<Object> parameters = new ArrayList<>();
        parameters.add(runId);
        if (canaryOnly) parameters.add(runId);
        parameters.add(limit);
        List<QueueItem> items = jdbc.query("""
                select id,customer_id,generation,attempt_count
                from feishu_bitable_sync_queue
                where run_id=? and status in ('PENDING','RETRYING') and next_attempt_at<=now()
                """ + canaryFilter + " order by created_at limit ? for update skip locked",
                (rs, index) -> new QueueItem(rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getLong("generation"),
                rs.getInt("attempt_count")), parameters.toArray());
        for (QueueItem item : items) {
            jdbc.update("""
                    update feishu_bitable_sync_queue set status='SENDING',updated_at=now()
                    where id=? and generation=?
                    """, item.id(), item.generation());
        }
        return items;
    }

    private void deliverBatch(RunRow run, FeishuIntegrationService.RemoteContext remote, List<QueueItem> items) {
        List<UUID> customerIds = items.stream().map(QueueItem::customerId).toList();
        Map<UUID, FeishuCustomerSnapshotRepository.CustomerSnapshot> byCustomer = new HashMap<>();
        snapshots.find(run.organizationId(), customerIds).forEach(item -> byCustomer.put(item.customerId(), item));
        Map<UUID, LinkRow> links = links(run.integrationId(), customerIds);
        boolean force = "MANUAL".equals(run.type()) || "RECONCILIATION".equals(run.type());
        List<DeliveryItem> creates = new ArrayList<>();
        List<DeliveryItem> updates = new ArrayList<>();
        for (QueueItem item : items) {
            FeishuCustomerSnapshotRepository.CustomerSnapshot snapshot = byCustomer.get(item.customerId());
            if (snapshot == null) {
                failItem(run.id(), item, "FEISHU_SOURCE_CUSTOMER_NOT_FOUND");
                continue;
            }
            LinkRow link = links.get(item.customerId());
            if (!force && link != null && link.snapshotHash().equals(snapshot.hash())) {
                markSkipped(run.id(), item);
                continue;
            }
            DeliveryItem delivery = new DeliveryItem(item, snapshot, link);
            if (link == null) creates.add(delivery); else updates.add(delivery);
        }
        if (!creates.isEmpty()) deliverCreates(run, remote, creates);
        if (!updates.isEmpty()) deliverUpdates(run, remote, updates);
    }

    private void deliverCreates(RunRow run, FeishuIntegrationService.RemoteContext remote,
                                List<DeliveryItem> deliveries) {
        Instant syncedAt = Instant.now();
        List<Map<String, Object>> fields = deliveries.stream()
                .map(item -> item.snapshot().remoteFields(remote.remoteNames(), syncedAt)).toList();
        try {
            List<String> recordIds = client.batchCreate(remote.credentials(), remote.integration().baseAppToken(),
                    remote.integration().tableId(), fields);
            for (int index = 0; index < deliveries.size(); index++) {
                markDelivered(run, deliveries.get(index), recordIds.get(index), syncedAt);
            }
        } catch (RuntimeException exception) {
            String code = integrations.safeError(exception);
            deliveries.forEach(item -> failItem(run.id(), item.queue(), code));
        }
    }

    private void deliverUpdates(RunRow run, FeishuIntegrationService.RemoteContext remote,
                                List<DeliveryItem> deliveries) {
        Instant syncedAt = Instant.now();
        List<FeishuBitableClient.RecordUpdate> records = deliveries.stream()
                .map(item -> new FeishuBitableClient.RecordUpdate(item.link().recordId(),
                        item.snapshot().remoteFields(remote.remoteNames(), syncedAt))).toList();
        try {
            client.batchUpdate(remote.credentials(), remote.integration().baseAppToken(),
                    remote.integration().tableId(), records);
            for (DeliveryItem item : deliveries) markDelivered(run, item, item.link().recordId(), syncedAt);
        } catch (RuntimeException exception) {
            String code = integrations.safeError(exception);
            deliveries.forEach(item -> failItem(run.id(), item.queue(), code));
        }
    }

    private void markDelivered(RunRow run, DeliveryItem item, String recordId, Instant syncedAt) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into feishu_bitable_record_links
                    (integration_id,organization_id,customer_id,record_id,source_version,snapshot_hash,synced_at)
                    values (?,?,?,?,?,?,?)
                    on conflict (integration_id,customer_id) do update set
                        record_id=excluded.record_id,source_version=excluded.source_version,
                        snapshot_hash=excluded.snapshot_hash,synced_at=excluded.synced_at
                    """, run.integrationId(), run.organizationId(), item.snapshot().customerId(), recordId,
                    item.snapshot().sourceVersion(), item.snapshot().hash(), Timestamp.from(syncedAt));
            int changed = jdbc.update("""
                    update feishu_bitable_sync_queue set status='DELIVERED',attempt_count=attempt_count+1,
                           last_error_code=null,updated_at=now()
                    where id=? and generation=? and status='SENDING'
                    """, item.queue().id(), item.queue().generation());
            if (changed > 0) jdbc.update("""
                    update feishu_bitable_sync_runs set success_count=success_count+1,updated_at=now() where id=?
                    """, run.id());
        });
    }

    private void markSkipped(UUID runId, QueueItem item) {
        int changed = jdbc.update("""
                update feishu_bitable_sync_queue set status='DELIVERED',attempt_count=attempt_count+1,
                       last_error_code=null,updated_at=now()
                where id=? and generation=? and status='SENDING'
                """, item.id(), item.generation());
        if (changed > 0) jdbc.update("""
                update feishu_bitable_sync_runs set skipped_count=skipped_count+1,updated_at=now() where id=?
                """, runId);
    }

    private void failItem(UUID runId, QueueItem item, String code) {
        int attempt = item.attemptCount() + 1;
        boolean terminal = attempt >= 5;
        Instant next = Instant.now().plus(switch (attempt) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 15;
            case 4 -> 60;
            default -> 180;
        }, ChronoUnit.MINUTES);
        int changed = jdbc.update("""
                update feishu_bitable_sync_queue set status=?,attempt_count=?,next_attempt_at=?,
                       last_error_code=?,updated_at=now()
                where id=? and generation=? and status='SENDING'
                """, terminal ? "FAILED" : "RETRYING", attempt, Timestamp.from(next), code,
                item.id(), item.generation());
        if (changed > 0 && terminal) jdbc.update("""
                update feishu_bitable_sync_runs set failed_count=failed_count+1,last_error_code=?,updated_at=now()
                where id=?
                """, code, runId);
    }

    private Map<UUID, LinkRow> links(UUID integrationId, List<UUID> customerIds) {
        if (customerIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(customerIds.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(integrationId);
        parameters.addAll(customerIds);
        Map<UUID, LinkRow> result = new HashMap<>();
        jdbc.query("select customer_id,record_id,snapshot_hash from feishu_bitable_record_links "
                        + "where integration_id=? and customer_id in (" + placeholders + ")",
                (RowCallbackHandler) rs -> result.put(rs.getObject("customer_id", UUID.class),
                        new LinkRow(rs.getString("record_id"), rs.getString("snapshot_hash"))),
                parameters.toArray());
        return result;
    }

    private void finishRunIfDone(UUID runId, UUID integrationId) {
        Integer remaining = jdbc.queryForObject("""
                select count(*) from feishu_bitable_sync_queue
                where run_id=? and status in ('PENDING','SENDING','RETRYING')
                """, Integer.class, runId);
        if (remaining != null && remaining > 0) return;
        Integer failed = jdbc.queryForObject("""
                select count(*) from feishu_bitable_sync_queue where run_id=? and status='FAILED'
                """, Integer.class, runId);
        String status = failed != null && failed > 0 ? "COMPLETED_WITH_ERRORS" : "COMPLETED";
        jdbc.update("""
                update feishu_bitable_sync_runs set status=?,completed_at=now(),updated_at=now()
                where id=? and status='RUNNING'
                """, status, runId);
        if ("COMPLETED".equals(status)) jdbc.update("""
                update feishu_bitable_integrations set last_success_at=now(),last_error_code=null,updated_at=now()
                where id=?
                """, integrationId);
    }

    private void failRun(UUID runId, String code) {
        jdbc.update("""
                update feishu_bitable_sync_runs set status='FAILED',last_error_code=?,completed_at=now(),updated_at=now()
                where id=?
                """, code, runId);
    }

    private CanaryState canaryState(RunRow run) {
        if (!"INITIAL".equals(run.type())) return new CanaryState(false, false, 0, 0);
        Map<String, Object> counts = jdbc.queryForMap("""
                select success_count+skipped_count completed,failed_count
                from feishu_bitable_sync_runs where id=?
                """, run.id());
        int target = Math.min(10, run.totalCount());
        int completed = ((Number) counts.get("completed")).intValue();
        int failed = ((Number) counts.get("failed_count")).intValue();
        return new CanaryState(completed < target, failed > 0 && completed < target, target, completed);
    }

    record QueueItem(UUID id, UUID customerId, long generation, int attemptCount) {}
    private record RunRow(UUID id, UUID integrationId, UUID organizationId, String type, String status, int totalCount) {}
    private record CanaryState(boolean active, boolean failed, int target, int completed) {}
    private record LinkRow(String recordId, String snapshotHash) {}
    private record DeliveryItem(QueueItem queue, FeishuCustomerSnapshotRepository.CustomerSnapshot snapshot,
                                LinkRow link) {}
}
