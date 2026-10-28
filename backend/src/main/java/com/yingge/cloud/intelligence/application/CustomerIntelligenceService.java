package com.yingge.cloud.intelligence.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.intelligence.api.CustomerIntelligenceDtos;
import com.yingge.cloud.intelligence.infrastructure.CustomerIntelligenceStore;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerIntelligenceService {
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final CustomerService customers;
    private final CustomerIntelligenceQueue queue;
    private final CustomerIntelligenceStore store;
    private final AuditService audit;
    private final CustomerIntelligenceRuntimeMonitor runtimeMonitor;

    public CustomerIntelligenceService(
            JdbcTemplate jdbc,
            CurrentActorProvider actors,
            PermissionService permissions,
            CustomerService customers,
            CustomerIntelligenceQueue queue,
            CustomerIntelligenceStore store,
            AuditService audit,
            CustomerIntelligenceRuntimeMonitor runtimeMonitor) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.customers = customers;
        this.queue = queue;
        this.store = store;
        this.audit = audit;
        this.runtimeMonitor = runtimeMonitor;
    }

    @Transactional(readOnly = true)
    public CustomerIntelligenceDtos.Overview overview(UUID customerId) {
        CurrentActor actor = actors.require();
        customers.requireVisibleEntity(customerId, actor);
        SettingsRow settings = settings(actor.organizationId());
        JobState state = jobState(actor.organizationId(), customerId);
        var versions = store.versions(actor.organizationId(), customerId, 1);
        return new CustomerIntelligenceDtos.Overview(
                customerId, settings.enabled(), state != null && List.of("PENDING", "RUNNING").contains(state.status()),
                state == null ? null : state.status(), versions.isEmpty() ? null : versions.getFirst());
    }

    @Transactional(readOnly = true)
    public List<CustomerIntelligenceDtos.Version> versions(UUID customerId) {
        CurrentActor actor = actors.require();
        customers.requireVisibleEntity(customerId, actor);
        return store.versions(actor.organizationId(), customerId, 30);
    }

    @Transactional
    public CustomerIntelligenceDtos.Overview reanalyze(UUID customerId) {
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        customers.requireTimelineEditable(customer, actor);
        if (customer.getArchivedAt() != null) {
            throw ApiException.conflict(
                    "ARCHIVED_CUSTOMER_ANALYSIS_FORBIDDEN",
                    "归档客户不能重新分析，请先恢复客户");
        }
        queue.enqueue(actor.organizationId(), customerId,
                CustomerIntelligenceQueue.TriggerType.MANUAL, actor.membershipId());
        audit.record(actor, "CUSTOMER_INTELLIGENCE_REQUESTED", "CUSTOMER", customerId.toString(),
                "SUCCESS", Map.of("trigger", "MANUAL"));
        return overview(customerId);
    }

    @Transactional(readOnly = true)
    public CustomerIntelligenceDtos.Settings adminSettings() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return response(actor.organizationId());
    }

    @Transactional(readOnly = true)
    public CustomerIntelligenceDtos.RuntimeStatus runtimeStatus() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return runtimeMonitor.snapshot(actor.organizationId());
    }

    @Transactional
    public CustomerIntelligenceDtos.Settings updateSettings(
            CustomerIntelligenceDtos.UpdateSettingsRequest request) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        String baseUrl = validateBaseUrl(request.embeddingBaseUrl());
        String model = request.embeddingModel().trim();
        SettingsRow current = settings(actor.organizationId());
        if (request.expectedVersion() != null && request.expectedVersion() != current.version()) {
            throw ApiException.conflict("STALE_VERSION", "客户智能分析配置已被其他管理员更新");
        }
        int updated = jdbc.update("""
                update customer_intelligence_settings
                set enabled=?, auto_profile_analysis=?, auto_timeline_analysis=?,
                    embedding_base_url=?, embedding_model=?, recent_interaction_limit=?,
                    semantic_chunk_limit=?, backfill_enabled=?, backfill_paused=?,
                    nightly_rate_per_minute=?, updated_by_membership_id=?, updated_at=now(),
                    version=version+1
                where organization_id=? and version=?
                """, request.enabled(), request.autoProfileAnalysis(), request.autoTimelineAnalysis(),
                baseUrl, model, request.recentInteractionLimit(), request.semanticChunkLimit(),
                request.backfillEnabled(), request.backfillPaused(), request.nightlyRatePerMinute(),
                actor.membershipId(), actor.organizationId(), current.version());
        if (updated != 1) throw ApiException.conflict("STALE_VERSION", "客户智能分析配置已被其他管理员更新");
        audit.record(actor, "CUSTOMER_INTELLIGENCE_SETTINGS_UPDATED", "CUSTOMER_INTELLIGENCE_SETTINGS",
                actor.organizationId().toString(), "SUCCESS", Map.of(
                        "enabled", request.enabled(), "model", model,
                        "backfillPaused", request.backfillPaused()));
        return response(actor.organizationId());
    }

    @Transactional
    public CustomerIntelligenceDtos.Settings setBackfillPaused(boolean paused) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        jdbc.update("""
                update customer_intelligence_settings
                set backfill_paused=?, updated_by_membership_id=?, updated_at=now(), version=version+1
                where organization_id=?
                """, paused, actor.membershipId(), actor.organizationId());
        audit.record(actor, paused ? "CUSTOMER_INTELLIGENCE_BACKFILL_PAUSED"
                        : "CUSTOMER_INTELLIGENCE_BACKFILL_RESUMED",
                "CUSTOMER_INTELLIGENCE_SETTINGS", actor.organizationId().toString(), "SUCCESS", null);
        return response(actor.organizationId());
    }

    private CustomerIntelligenceDtos.Settings response(UUID organizationId) {
        SettingsRow value = settings(organizationId);
        long pending = jdbc.queryForObject("""
                select count(*) from customer_analysis_jobs
                where organization_id=? and status in ('PENDING','RUNNING')
                """, Long.class, organizationId);
        long completed = jdbc.queryForObject("""
                select count(distinct customer_id) from customer_analysis_versions
                where organization_id=? and status='SUCCEEDED'
                """, Long.class, organizationId);
        long total = jdbc.queryForObject("""
                select count(*) from customers where organization_id=? and archived_at is null
                """, Long.class, organizationId);
        return new CustomerIntelligenceDtos.Settings(
                value.enabled(), value.autoProfile(), value.autoTimeline(), value.embeddingBaseUrl(),
                value.embeddingModel(), value.recentLimit(), value.semanticLimit(), value.backfillEnabled(),
                value.backfillPaused(), value.rate(), pending, completed, total, value.updatedAt(), value.version());
    }

    private SettingsRow settings(UUID organizationId) {
        return jdbc.query("""
                select enabled, auto_profile_analysis, auto_timeline_analysis,
                       embedding_base_url, embedding_model, recent_interaction_limit,
                       semantic_chunk_limit, backfill_enabled, backfill_paused,
                       nightly_rate_per_minute, updated_at, version
                from customer_intelligence_settings where organization_id=?
                """, (row, index) -> new SettingsRow(
                row.getBoolean("enabled"), row.getBoolean("auto_profile_analysis"),
                row.getBoolean("auto_timeline_analysis"), row.getString("embedding_base_url"),
                row.getString("embedding_model"), row.getInt("recent_interaction_limit"),
                row.getInt("semantic_chunk_limit"), row.getBoolean("backfill_enabled"),
                row.getBoolean("backfill_paused"), row.getInt("nightly_rate_per_minute"),
                row.getTimestamp("updated_at").toInstant(), row.getLong("version")), organizationId)
                .stream().findFirst().orElse(new SettingsRow(
                        true, true, true, "http://embedding:8080", "bge-m3",
                        10, 12, true, false, 10, Instant.now(), 0));
    }

    private JobState jobState(UUID organizationId, UUID customerId) {
        return jdbc.query("""
                select status, updated_at from customer_analysis_jobs
                where organization_id=? and customer_id=?
                order by created_at desc limit 1
                """, (row, index) -> new JobState(
                row.getString("status"), row.getTimestamp("updated_at").toInstant()),
                organizationId, customerId).stream().findFirst().orElse(null);
    }

    private String validateBaseUrl(String raw) {
        String value = raw.trim().replaceFirst("/+$", "");
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("EMBEDDING_BASE_URL_INVALID", "向量服务地址必须是有效的 HTTP(S) 地址");
        }
    }

    private record SettingsRow(
            boolean enabled, boolean autoProfile, boolean autoTimeline,
            String embeddingBaseUrl, String embeddingModel,
            int recentLimit, int semanticLimit,
            boolean backfillEnabled, boolean backfillPaused, int rate,
            Instant updatedAt, long version) {}
    private record JobState(String status, Instant updatedAt) {}
}
