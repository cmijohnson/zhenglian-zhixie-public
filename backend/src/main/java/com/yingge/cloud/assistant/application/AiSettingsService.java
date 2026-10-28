package com.yingge.cloud.assistant.application;

import com.yingge.cloud.assistant.api.AssistantAdminDtos;
import com.yingge.cloud.assistant.infrastructure.AiSecretCipher;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class AiSettingsService {
    private final JdbcTemplate jdbc;
    private final AiProperties defaults;
    private final AiSecretCipher cipher;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;

    public AiSettingsService(JdbcTemplate jdbc, AiProperties defaults, AiSecretCipher cipher,
                             CurrentActorProvider actors, PermissionService permissions, AuditService audit) {
        this.jdbc = jdbc;
        this.defaults = defaults;
        this.cipher = cipher;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public AssistantAdminDtos.SettingsResponse adminSettings() {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        return response(actor.organizationId());
    }

    @Transactional
    public AssistantAdminDtos.SettingsResponse update(AssistantAdminDtos.UpdateRequest request) {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        String baseUrl = validateBaseUrl(request.baseUrl());
        String model = request.model().trim();
        if (request.clearApiKey() && request.apiKey() != null && !request.apiKey().isBlank()) {
            throw ApiException.badRequest("AI_KEY_UPDATE_CONFLICT", "不能同时清除和更新 API 密钥");
        }
        Stored stored = stored(actor.organizationId());
        long currentVersion = stored == null ? 0 : stored.version();
        if (request.expectedVersion() != null && request.expectedVersion() != currentVersion) {
            throw ApiException.conflict("STALE_VERSION", "智能助手配置已被其他管理员更新，请刷新后重试");
        }
        String encrypted = stored == null ? null : stored.encryptedApiKey();
        boolean keyChanged = request.apiKey() != null && !request.apiKey().isBlank();
        if (request.clearApiKey()) encrypted = null;
        else if (keyChanged) encrypted = cipher.encrypt(request.apiKey().trim());

        Instant now = Instant.now();
        if (stored == null) {
            try {
                jdbc.update("""
                        insert into assistant_settings
                            (organization_id, enabled, base_url, model, encrypted_api_key,
                             updated_by_membership_id, updated_at, version)
                        values (?, ?, ?, ?, ?, ?, ?, 1)
                        """, actor.organizationId(), request.enabled(), baseUrl, model, encrypted,
                        actor.membershipId(), Timestamp.from(now));
            } catch (DuplicateKeyException exception) {
                throw ApiException.conflict("STALE_VERSION", "智能助手配置已被其他管理员创建，请刷新后重试");
            }
        } else {
            int count = jdbc.update("""
                    update assistant_settings
                    set enabled=?, base_url=?, model=?, encrypted_api_key=?, updated_by_membership_id=?,
                        updated_at=?, version=version+1
                    where organization_id=? and version=?
                    """, request.enabled(), baseUrl, model, encrypted, actor.membershipId(), Timestamp.from(now),
                    actor.organizationId(), stored.version());
            if (count != 1) throw ApiException.conflict("STALE_VERSION", "智能助手配置已被其他管理员更新，请刷新后重试");
        }
        audit.record(actor, "AI_SETTINGS_UPDATED", "ASSISTANT_SETTINGS", actor.organizationId().toString(),
                "SUCCESS", Map.of(
                        "enabled", request.enabled(), "baseUrl", baseUrl, "model", model,
                        "apiKeyChanged", keyChanged, "apiKeyCleared", request.clearApiKey()));
        return response(actor.organizationId());
    }

    @Transactional(readOnly = true)
    public EffectiveConfig effective(UUID organizationId) {
        Stored stored = stored(organizationId);
        boolean enabled = stored == null || stored.enabled();
        String baseUrl = stored == null ? defaultBaseUrl() : stored.baseUrl();
        String model = stored == null ? defaultModel() : stored.model();
        String apiKey = stored != null && stored.encryptedApiKey() != null
                ? cipher.decrypt(stored.encryptedApiKey()) : defaults.apiKey();
        return new EffectiveConfig(enabled, baseUrl, apiKey, model,
                defaults.timeout() == null ? Duration.ofSeconds(90) : defaults.timeout());
    }

    @Transactional(readOnly = true)
    public RuntimeMetadata metadata(UUID organizationId) {
        Stored stored = stored(organizationId);
        boolean keyConfigured = (stored != null && stored.encryptedApiKey() != null) || defaults.configured();
        return new RuntimeMetadata(stored == null || stored.enabled(),
                stored == null ? defaultModel() : stored.model(), keyConfigured);
    }

    private AssistantAdminDtos.SettingsResponse response(UUID organizationId) {
        Stored stored = stored(organizationId);
        boolean databaseKey = stored != null && stored.encryptedApiKey() != null;
        boolean environmentKey = defaults.configured();
        return new AssistantAdminDtos.SettingsResponse(
                stored == null || stored.enabled(),
                stored == null ? defaultBaseUrl() : stored.baseUrl(),
                stored == null ? defaultModel() : stored.model(),
                databaseKey || environmentKey,
                databaseKey ? "DATABASE" : (environmentKey ? "ENVIRONMENT" : "NONE"),
                stored == null ? null : stored.updatedAt(),
                stored == null ? 0 : stored.version());
    }

    private Stored stored(UUID organizationId) {
        return jdbc.query("""
                        select enabled, base_url, model, encrypted_api_key, updated_at, version
                        from assistant_settings where organization_id=?
                        """, (result, row) -> new Stored(
                        result.getBoolean("enabled"), result.getString("base_url"), result.getString("model"),
                        result.getString("encrypted_api_key"), result.getTimestamp("updated_at").toInstant(),
                        result.getLong("version")), organizationId)
                .stream().findFirst().orElse(null);
    }

    private String validateBaseUrl(String raw) {
        String value = raw.trim();
        if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("AI_BASE_URL_INVALID", "API 地址必须是有效的 HTTP(S) 地址且不能包含账号或片段");
        }
    }

    private String defaultBaseUrl() {
        return defaults.baseUrl() == null || defaults.baseUrl().isBlank()
                ? "https://api.openai.com/v1" : defaults.baseUrl().replaceFirst("/+$", "");
    }

    private String defaultModel() {
        return defaults.model() == null || defaults.model().isBlank() ? "gpt-4.1-mini" : defaults.model();
    }

    private record Stored(boolean enabled, String baseUrl, String model, String encryptedApiKey,
                          Instant updatedAt, long version) {}

    public record EffectiveConfig(boolean enabled, String baseUrl, String apiKey, String model, Duration timeout) {
        public boolean configured() { return enabled && apiKey != null && !apiKey.isBlank(); }
    }

    public record RuntimeMetadata(boolean enabled, String model, boolean keyConfigured) {}
}
