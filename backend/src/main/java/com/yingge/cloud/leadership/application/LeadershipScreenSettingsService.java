package com.yingge.cloud.leadership.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.leadership.api.LeadershipScreenSettingsDtos;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class LeadershipScreenSettingsService {
    private static final List<String> SCENES =
            List.of("time", "culture", "briefing", "trend", "champions", "funnel", "mission",
                    "followUpRisk", "schoolComparison");
    private static final List<String> EVENTS =
            List.of("zeroBreakthrough", "customerAdded", "customerUpdated",
                    "milestone", "trendSurge", "conversionStage",
                    "taskPublished", "taskMilestone", "rankingChange", "teamMomentum");
    private static final Set<String> INTENSITIES = Set.of("SUBTLE", "CINEMATIC", "STRONG");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;

    public LeadershipScreenSettingsService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CurrentActorProvider actors,
            PermissionService permissions,
            AuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public LeadershipScreenSettingsDtos.Settings adminSettings() {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        return effective(actor.organizationId());
    }

    @Transactional(readOnly = true)
    public LeadershipScreenSettingsDtos.Settings effective(UUID organizationId) {
        Stored stored = stored(organizationId);
        if (stored == null) return defaults();
        return new LeadershipScreenSettingsDtos.Settings(
                stored.animationsEnabled(), stored.standbyEnabled(), stored.idleTimeoutMinutes(),
                stored.sceneDurationSeconds(), stored.eventDurationSeconds(), stored.motionIntensity(),
                stored.sceneOrder(), stored.enabledScenes(), stored.enabledEvents(),
                stored.featuredGrade(),
                stored.updatedAt(), stored.version());
    }

    @Transactional
    public LeadershipScreenSettingsDtos.Settings update(LeadershipScreenSettingsDtos.UpdateRequest request) {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        Validated validated = validate(request);
        Stored stored = stored(actor.organizationId());
        long currentVersion = stored == null ? 0 : stored.version();
        if (request.expectedVersion() != null && request.expectedVersion() != currentVersion) {
            throw ApiException.conflict("STALE_VERSION", "大屏配置已被其他管理员更新，请刷新后重试");
        }
        Instant now = Instant.now();
        if (stored == null) {
            int count = jdbc.update("""
                    insert into leadership_screen_settings (
                      organization_id, animations_enabled, standby_enabled, idle_timeout_minutes,
                      scene_duration_seconds, event_duration_seconds, motion_intensity,
                      scene_order, enabled_scenes, enabled_events,
                      featured_grade,
                      updated_by_membership_id, updated_at, version
                    ) values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), ?, ?, ?, 1)
                    on conflict (organization_id) do nothing
                    """, actor.organizationId(), request.animationsEnabled(), request.standbyEnabled(),
                    request.idleTimeoutMinutes(), request.sceneDurationSeconds(), request.eventDurationSeconds(),
                    validated.motionIntensity(), json(validated.sceneOrder()), json(validated.enabledScenes()),
                    json(validated.enabledEvents()), validated.featuredGrade(), actor.membershipId(), Timestamp.from(now));
            if (count != 1) {
                throw ApiException.conflict("STALE_VERSION", "大屏配置已被其他管理员更新，请刷新后重试");
            }
        } else {
            int count = jdbc.update("""
                    update leadership_screen_settings
                    set animations_enabled=?, standby_enabled=?, idle_timeout_minutes=?,
                        scene_duration_seconds=?, event_duration_seconds=?, motion_intensity=?,
                        scene_order=cast(? as jsonb), enabled_scenes=cast(? as jsonb),
                        enabled_events=cast(? as jsonb), featured_grade=?, updated_by_membership_id=?,
                        updated_at=?, version=version+1
                    where organization_id=? and version=?
                    """, request.animationsEnabled(), request.standbyEnabled(), request.idleTimeoutMinutes(),
                    request.sceneDurationSeconds(), request.eventDurationSeconds(), validated.motionIntensity(),
                    json(validated.sceneOrder()), json(validated.enabledScenes()), json(validated.enabledEvents()),
                    validated.featuredGrade(), actor.membershipId(), Timestamp.from(now), actor.organizationId(), stored.version());
            if (count != 1) {
                throw ApiException.conflict("STALE_VERSION", "大屏配置已被其他管理员更新，请刷新后重试");
            }
        }
        audit.record(actor, "LEADERSHIP_SCREEN_SETTINGS_UPDATED", "LEADERSHIP_SCREEN_SETTINGS",
                actor.organizationId().toString(), "SUCCESS", Map.of(
                        "animationsEnabled", request.animationsEnabled(),
                        "standbyEnabled", request.standbyEnabled(),
                        "motionIntensity", validated.motionIntensity(),
                        "featuredGrade", validated.featuredGrade(),
                        "enabledSceneCount", validated.enabledScenes().size(),
                        "enabledEventCount", validated.enabledEvents().size()));
        return effective(actor.organizationId());
    }

    private Validated validate(LeadershipScreenSettingsDtos.UpdateRequest request) {
        String intensity = request.motionIntensity().trim().toUpperCase();
        if (!INTENSITIES.contains(intensity)) {
            throw ApiException.badRequest("LEADERSHIP_INTENSITY_INVALID", "动画强度必须是 SUBTLE、CINEMATIC 或 STRONG");
        }
        if (new HashSet<>(request.sceneOrder()).size() != SCENES.size()
                || !new HashSet<>(request.sceneOrder()).equals(new HashSet<>(SCENES))) {
            throw ApiException.badRequest("LEADERSHIP_SCENE_ORDER_INVALID", "待机场景排序必须完整且不能重复");
        }
        if (!SCENES.containsAll(request.enabledScenes())
                || new HashSet<>(request.enabledScenes()).size() != request.enabledScenes().size()) {
            throw ApiException.badRequest("LEADERSHIP_SCENES_INVALID", "启用的待机场景包含未知或重复项目");
        }
        if (!EVENTS.containsAll(request.enabledEvents())
                || new HashSet<>(request.enabledEvents()).size() != request.enabledEvents().size()) {
            throw ApiException.badRequest("LEADERSHIP_EVENTS_INVALID", "启用的事件动画包含未知或重复项目");
        }
        String featuredGrade = request.featuredGrade().trim();
        if (!featuredGrade.matches("^(19|20)\\d{2}$")) {
            throw ApiException.badRequest("LEADERSHIP_FEATURED_GRADE_INVALID", "重点年级必须是四位年份");
        }
        List<String> enabledScenes = request.sceneOrder().stream()
                .filter(request.enabledScenes()::contains).toList();
        List<String> enabledEvents = EVENTS.stream().filter(request.enabledEvents()::contains).toList();
        return new Validated(intensity, List.copyOf(request.sceneOrder()), enabledScenes, enabledEvents,
                featuredGrade);
    }

    private Stored stored(UUID organizationId) {
        return jdbc.query("""
                select animations_enabled, standby_enabled, idle_timeout_minutes,
                       scene_duration_seconds, event_duration_seconds, motion_intensity,
                       scene_order::text, enabled_scenes::text, enabled_events::text,
                       featured_grade,
                       updated_at, version
                from leadership_screen_settings
                where organization_id=?
                """, (row, index) -> new Stored(
                row.getBoolean("animations_enabled"), row.getBoolean("standby_enabled"),
                row.getInt("idle_timeout_minutes"), row.getInt("scene_duration_seconds"),
                row.getInt("event_duration_seconds"), row.getString("motion_intensity"),
                strings(row.getString("scene_order")), strings(row.getString("enabled_scenes")),
                strings(row.getString("enabled_events")), row.getString("featured_grade"),
                row.getTimestamp("updated_at").toInstant(),
                row.getLong("version")), organizationId).stream().findFirst().orElse(null);
    }

    private LeadershipScreenSettingsDtos.Settings defaults() {
        return new LeadershipScreenSettingsDtos.Settings(
                true, true, 20, 12, 7, "CINEMATIC",
                SCENES, SCENES, EVENTS, "2026", null, 0);
    }

    private String json(List<String> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化大屏配置", exception);
        }
    }

    private List<String> strings(String value) {
        try {
            return mapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取大屏配置", exception);
        }
    }

    private record Validated(
            String motionIntensity,
            List<String> sceneOrder,
            List<String> enabledScenes,
            List<String> enabledEvents,
            String featuredGrade
    ) {}

    private record Stored(
            boolean animationsEnabled,
            boolean standbyEnabled,
            int idleTimeoutMinutes,
            int sceneDurationSeconds,
            int eventDurationSeconds,
            String motionIntensity,
            List<String> sceneOrder,
            List<String> enabledScenes,
            List<String> enabledEvents,
            String featuredGrade,
            Instant updatedAt,
            long version
    ) {}
}
