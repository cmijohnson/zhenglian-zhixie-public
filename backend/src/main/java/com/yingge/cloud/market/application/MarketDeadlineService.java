package com.yingge.cloud.market.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.market.api.MarketDtos;
import com.yingge.cloud.market.domain.CustomerClassLevel;
import com.yingge.cloud.market.domain.DeadlineCalculator;
import com.yingge.cloud.market.domain.InteractionType;
import com.yingge.cloud.market.domain.InteractionEffectPolicy;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class MarketDeadlineService {
    private static final Duration BACKFILL_LIMIT = Duration.ofHours(48);
    private static final Duration CLIENT_CLOCK_SKEW_TOLERANCE = Duration.ofMinutes(2);
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final CustomerService customers;
    private final AuditService audit;

    public MarketDeadlineService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                 CurrentActorProvider actors, PermissionService permissions,
                                 CustomerService customers, AuditService audit) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.actors = actors;
        this.permissions = permissions;
        this.customers = customers;
        this.audit = audit;
    }

    public Instant normalizeOccurredAt(Instant occurredAt, Instant recordedAt) {
        if (occurredAt == null) return recordedAt;
        if (occurredAt.isAfter(recordedAt.plus(CLIENT_CLOCK_SKEW_TOLERANCE))) {
            throw ApiException.badRequest("INTERACTION_TIME_IN_FUTURE", "沟通实际发生时间不能晚于系统当前时间");
        }
        return occurredAt.isAfter(recordedAt) ? recordedAt : occurredAt;
    }

    public void validateOccurredAt(Instant occurredAt, Instant recordedAt) {
        normalizeOccurredAt(occurredAt, recordedAt);
    }

    @Transactional
    public Evaluation evaluateInteraction(UUID organizationId, UUID customerId, UUID interactionId,
                                          InteractionType type, Instant occurredAt, Instant recordedAt,
                                          String fingerprint) {
        validateOccurredAt(occurredAt, recordedAt);
        Snapshot current = snapshotForUpdate(organizationId, customerId);
        UUID cycleId = ownershipCycleId(organizationId, customerId);
        if (current != null && !current.cycleId().equals(cycleId)) current = null;
        if (!type.isContactAttempt()) {
            var decision = InteractionEffectPolicy.decide(type, false, false, false, false, true);
            return ignored(organizationId, customerId, cycleId, interactionId, type.name(),
                    decision.reason(), current, decision.validOwnershipAction());
        }
        if (Duration.between(occurredAt, recordedAt).compareTo(BACKFILL_LIMIT) > 0) {
            var decision = InteractionEffectPolicy.decide(type, true, false, false, false, true);
            return ignored(organizationId, customerId, cycleId, interactionId, type.name(),
                    decision.reason(), current, decision.validOwnershipAction());
        }
        Long duplicateCount = jdbc.queryForObject("""
                select count(*) from interactions
                where organization_id=:organizationId and customer_id=:customerId
                  and id<>:interactionId and content_fingerprint=:fingerprint
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId).addValue("customerId", customerId)
                .addValue("interactionId", interactionId).addValue("fingerprint", fingerprint), Long.class);
        if (duplicateCount != null && duplicateCount > 0) {
            var decision = InteractionEffectPolicy.decide(type, false, true, false, false, true);
            return ignored(organizationId, customerId, cycleId, interactionId, type.name(),
                    decision.reason(), current, decision.validOwnershipAction());
        }
        Instant latestAppliedOccurredAt = jdbc.query("""
                select interaction.contacted_at
                from customer_deadline_events event
                join interactions interaction on interaction.id=event.interaction_id
                where event.organization_id=:organizationId and event.customer_id=:customerId
                  and event.cycle_id=:cycleId and event.applied=true
                order by interaction.contacted_at desc limit 1
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("customerId", customerId).addValue("cycleId", cycleId),
                (row, index) -> row.getTimestamp("contacted_at").toInstant()).stream().findFirst().orElse(null);
        if (latestAppliedOccurredAt != null && occurredAt.isBefore(latestAppliedOccurredAt)) {
            var decision = InteractionEffectPolicy.decide(type, false, false, true, false, true);
            return ignored(organizationId, customerId, cycleId, interactionId, type.name(),
                    decision.reason(), current, decision.validOwnershipAction());
        }
        CustomerClassLevel level = currentLevel(organizationId, customerId);
        if (level == null || level == CustomerClassLevel.C) {
            var decision = InteractionEffectPolicy.decide(type, false, false, false, false, false);
            return ignored(organizationId, customerId, cycleId, interactionId, type.name(),
                    decision.reason(), current, decision.validOwnershipAction());
        }
        Rule rule = activeRule(organizationId, recordedAt);
        boolean noReplyAlreadyUsed = (current != null && current.noReplyExtensionUsed())
                || noReplyUsedInCycle(organizationId, customerId, cycleId);
        if (type == InteractionType.OUTBOUND_NO_REPLY && noReplyAlreadyUsed) {
            var decision = InteractionEffectPolicy.decide(type, false, false, false, true, true);
            return ignored(organizationId, customerId, cycleId, interactionId, type.name(),
                    decision.reason(), current, decision.validOwnershipAction());
        }
        Instant calculated;
        boolean noReplyUsed = current != null && current.noReplyExtensionUsed();
        if (type == InteractionType.OUTBOUND_NO_REPLY) {
            Instant baseline = current == null
                    ? calculate(rule, occurredAt, level)
                    : current.deadline();
            calculated = baseline.plus(Duration.ofHours(24));
            noReplyUsed = true;
        } else {
            calculated = calculate(rule, occurredAt, level);
            if (current != null && current.deadline().isAfter(calculated)) calculated = current.deadline();
        }
        upsertSnapshot(organizationId, customerId, cycleId, level, rule, interactionId, calculated, noReplyUsed);
        appendEvent(organizationId, customerId, cycleId, interactionId, null, type.name(), true,
                "EFFECTIVE_CONTACT", current == null ? null : current.deadline(), calculated, rule.id());
        return new Evaluation(true, true, "EFFECTIVE_CONTACT", calculated);
    }

    @Transactional
    public void recalculateForClassification(UUID organizationId, UUID customerId, UUID classificationId,
                                             CustomerClassLevel level, Instant classifiedAt) {
        Snapshot current = snapshotForUpdate(organizationId, customerId);
        UUID cycleId = ownershipCycleId(organizationId, customerId);
        if (current != null && !current.cycleId().equals(cycleId)) current = null;
        if (level == CustomerClassLevel.C) {
            jdbc.update("delete from customer_deadline_snapshots where organization_id=:organizationId and customer_id=:customerId",
                    params(organizationId, customerId));
            appendEvent(organizationId, customerId, cycleId, null, classificationId,
                    "CLASSIFICATION_CHANGED", true, "C_CLASS_HAS_NO_DEADLINE",
                    current == null ? null : current.deadline(), null, null);
            return;
        }
        Rule rule = activeRule(organizationId, classifiedAt);
        Instant calculated = calculate(rule, classifiedAt, level);
        if (current != null && current.deadline().isAfter(calculated)) calculated = current.deadline();
        upsertSnapshot(organizationId, customerId, cycleId, level, rule,
                current == null ? null : current.baseInteractionId(), calculated,
                (current != null && current.noReplyExtensionUsed())
                        || noReplyUsedInCycle(organizationId, customerId, cycleId));
        appendEvent(organizationId, customerId, cycleId, null, classificationId,
                "CLASSIFICATION_CHANGED", true, "DEADLINE_RECALCULATED",
                current == null ? null : current.deadline(), calculated, rule.id());
    }

    @Transactional(readOnly = true)
    public List<MarketDtos.DeadlineRuleResponse> rules() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return jdbc.query("""
                select * from market_deadline_rule_versions
                where organization_id=:organizationId order by version desc
                """, new MapSqlParameterSource("organizationId", actor.organizationId()), this::ruleResponse);
    }

    @Transactional
    public int refreshSnapshotsForActiveRules() {
        List<Map<String, Object>> stale = jdbc.queryForList("""
                select customer.organization_id, customer.id customer_id,
                       classification.id classification_id, classification.level,
                       classification.created_at classification_at,
                       coalesce(state.ownership_cycle,0) ownership_cycle
                from customers customer
                join lateral (select item.id,item.level,item.created_at
                    from customer_classifications item where item.customer_id=customer.id
                    order by item.revision desc limit 1) classification on classification.level in ('A','B')
                join lateral (select rule.id,rule.mode
                    from market_deadline_rule_versions rule
                    where rule.organization_id=customer.organization_id and rule.effective_from<=now()
                    order by rule.effective_from desc,rule.version desc limit 1) active_rule on true
                left join customer_deadline_snapshots snapshot on snapshot.customer_id=customer.id
                left join customer_ownership_states state on state.customer_id=customer.id
                  and state.organization_id=customer.organization_id
                where customer.archived_at is null
                  and (snapshot.customer_id is null
                    or snapshot.rule_version_id<>active_rule.id or snapshot.mode<>active_rule.mode
                    or snapshot.ownership_cycle<>coalesce(state.ownership_cycle,0))
                for update of customer skip locked
                """, Map.of());
        for (Map<String, Object> row : stale) {
            UUID organizationId = (UUID) row.get("organization_id");
            UUID customerId = (UUID) row.get("customer_id");
            UUID classificationId = (UUID) row.get("classification_id");
            CustomerClassLevel level = CustomerClassLevel.valueOf((String) row.get("level"));
            long ownershipCycle = ((Number) row.get("ownership_cycle")).longValue();
            UUID cycleId = ownershipCycleId(organizationId, customerId, ownershipCycle);
            Instant base = latestAppliedOccurredAt(organizationId, customerId, cycleId);
            if (base == null) base = ((java.sql.Timestamp) row.get("classification_at")).toInstant();
            Snapshot previous = snapshotForUpdate(organizationId, customerId);
            Rule rule = activeRule(organizationId, Instant.now());
            Instant calculated = calculate(rule, base, level);
            boolean noReplyUsed = noReplyUsedInCycle(organizationId, customerId, cycleId);
            upsertSnapshot(organizationId, customerId, cycleId, level, rule,
                    previous == null ? null : previous.baseInteractionId(), calculated, noReplyUsed);
            appendEvent(organizationId, customerId, cycleId, null, classificationId,
                    "ACTIVE_RULE_REFRESHED", true, "ACTIVE_RULE_VERSION_CHANGED",
                    previous == null ? null : previous.deadline(), calculated, rule.id());
        }
        return stale.size();
    }

    @Transactional
    public MarketDtos.DeadlineRuleResponse createRule(MarketDtos.DeadlineRuleRequest request) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        String mode = request.mode() == null ? "SHADOW" : request.mode().trim().toUpperCase();
        if (!"SHADOW".equals(mode) && !"ENFORCED".equals(mode)) {
            throw ApiException.badRequest("DEADLINE_MODE_INVALID", "期限模式只能为 SHADOW 或 ENFORCED");
        }
        boolean weekendsCount = request.weekendsCount() == null || request.weekendsCount();
        List<MarketDtos.DateRange> peaks = request.peakPeriods() == null ? List.of() : List.copyOf(request.peakPeriods());
        List<MarketDtos.DateRange> pauses = request.pausePeriods() == null ? List.of() : List.copyOf(request.pausePeriods());
        try {
            DeadlineCalculator.requireValidRanges(peaks, "旺季");
            DeadlineCalculator.requireValidRanges(pauses, "暂停");
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("DEADLINE_DATE_RANGE_INVALID", exception.getMessage());
        }
        int aPeak = hours(request.aPeakHours(), 72);
        int bPeak = hours(request.bPeakHours(), 96);
        int aOffPeak = hours(request.aOffPeakHours(), 168);
        int bOffPeak = hours(request.bOffPeakHours(), 216);
        jdbc.queryForObject("select id from organizations where id=:organizationId for update",
                new MapSqlParameterSource("organizationId", actor.organizationId()), UUID.class);
        Integer nextVersion = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from market_deadline_rule_versions
                where organization_id=:organizationId
                """, new MapSqlParameterSource("organizationId", actor.organizationId()), Integer.class);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into market_deadline_rule_versions (
                    id, organization_id, version, effective_from, mode, peak_periods, pause_periods,
                    a_peak_hours, b_peak_hours, a_off_peak_hours, b_off_peak_hours,
                    weekends_count, created_by_membership_id
                ) values (
                    :id, :organizationId, :version, :effectiveFrom, :mode,
                    cast(:peaks as jsonb), cast(:pauses as jsonb), :aPeak, :bPeak,
                    :aOffPeak, :bOffPeak, :weekendsCount, :membershipId
                )
                """, new MapSqlParameterSource().addValue("id", id)
                .addValue("organizationId", actor.organizationId()).addValue("version", nextVersion)
                .addValue("effectiveFrom", sqlTimestamp(request.effectiveFrom())).addValue("mode", mode)
                .addValue("peaks", json(peaks)).addValue("pauses", json(pauses))
                .addValue("aPeak", aPeak).addValue("bPeak", bPeak)
                .addValue("aOffPeak", aOffPeak).addValue("bOffPeak", bOffPeak)
                .addValue("weekendsCount", weekendsCount)
                .addValue("membershipId", actor.membershipId()));
        audit.record(actor, "MARKET_DEADLINE_RULE_CREATED", "MARKET_DEADLINE_RULE", id.toString(), "SUCCESS",
                Map.of("version", nextVersion, "mode", mode, "effectiveFrom", request.effectiveFrom().toString()));
        return findRule(actor.organizationId(), id);
    }

    @Transactional(readOnly = true)
    public MarketDtos.DeadlineSnapshot snapshot(UUID customerId) {
        return findSnapshot(customerId)
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_DEADLINE_NOT_FOUND", "该客户当前没有 A/B 期限影子结果"));
    }

    @Transactional(readOnly = true)
    public Optional<MarketDtos.DeadlineSnapshot> findSnapshot(UUID customerId) {
        CurrentActor actor = actors.require();
        customers.requireVisibleEntity(customerId, actor);
        return jdbc.query("""
                select * from customer_deadline_snapshots
                where organization_id=:organizationId and customer_id=:customerId
                """, params(actor.organizationId(), customerId), (row, index) -> new MarketDtos.DeadlineSnapshot(
                row.getObject("customer_id", UUID.class), row.getObject("cycle_id", UUID.class),
                CustomerClassLevel.valueOf(row.getString("classification_level")),
                row.getObject("rule_version_id", UUID.class), row.getObject("base_interaction_id", UUID.class),
                row.getTimestamp("computed_deadline").toInstant(), row.getBoolean("no_reply_extension_used"),
                row.getString("mode"), row.getTimestamp("calculated_at").toInstant())).stream().findFirst();
    }

    private Evaluation ignored(UUID organizationId, UUID customerId, UUID cycleId, UUID interactionId,
                               String eventType, String reason, Snapshot current, boolean validOwnershipAction) {
        appendEvent(organizationId, customerId, cycleId, interactionId, null, eventType, false, reason,
                current == null ? null : current.deadline(), current == null ? null : current.deadline(),
                current == null ? null : current.ruleId());
        return new Evaluation(false, validOwnershipAction, reason, current == null ? null : current.deadline());
    }

    private void appendEvent(UUID organizationId, UUID customerId, UUID cycleId, UUID interactionId,
                             UUID classificationId, String eventType, boolean applied, String reason,
                             Instant previousDeadline, Instant calculatedDeadline, UUID ruleId) {
        jdbc.update("""
                insert into customer_deadline_events (
                    id, organization_id, customer_id, cycle_id, interaction_id, classification_id,
                    event_type, applied, reason, previous_deadline, calculated_deadline, rule_version_id
                ) values (
                    :id, :organizationId, :customerId, :cycleId, :interactionId, :classificationId,
                    :eventType, :applied, :reason, :previousDeadline, :calculatedDeadline, :ruleId
                )
                """, new MapSqlParameterSource().addValue("id", UUID.randomUUID())
                .addValue("organizationId", organizationId).addValue("customerId", customerId)
                .addValue("cycleId", cycleId).addValue("interactionId", interactionId)
                .addValue("classificationId", classificationId).addValue("eventType", eventType)
                .addValue("applied", applied).addValue("reason", reason)
                .addValue("previousDeadline", sqlTimestamp(previousDeadline))
                .addValue("calculatedDeadline", sqlTimestamp(calculatedDeadline))
                .addValue("ruleId", ruleId));
    }

    private void upsertSnapshot(UUID organizationId, UUID customerId, UUID cycleId,
                                CustomerClassLevel level, Rule rule, UUID interactionId,
                                Instant deadline, boolean noReplyUsed) {
        jdbc.update("""
                insert into customer_deadline_snapshots (
                    customer_id, organization_id, cycle_id, ownership_cycle, classification_level, rule_version_id,
                    base_interaction_id, computed_deadline, no_reply_extension_used, mode, calculated_at
                ) values (
                    :customerId, :organizationId, :cycleId, :ownershipCycle, :level, :ruleId,
                    :interactionId, :deadline, :noReplyUsed, :mode, now()
                ) on conflict (customer_id) do update set
                    cycle_id=excluded.cycle_id, ownership_cycle=excluded.ownership_cycle,
                    classification_level=excluded.classification_level,
                    rule_version_id=excluded.rule_version_id,
                    base_interaction_id=coalesce(excluded.base_interaction_id, customer_deadline_snapshots.base_interaction_id),
                    computed_deadline=excluded.computed_deadline,
                    no_reply_extension_used=excluded.no_reply_extension_used,
                    mode=excluded.mode, calculated_at=now()
                """, new MapSqlParameterSource().addValue("customerId", customerId)
                .addValue("organizationId", organizationId).addValue("cycleId", cycleId)
                .addValue("ownershipCycle", ownershipCycleNumber(organizationId, customerId))
                .addValue("level", level.name()).addValue("ruleId", rule.id())
                .addValue("interactionId", interactionId).addValue("deadline", sqlTimestamp(deadline))
                .addValue("noReplyUsed", noReplyUsed).addValue("mode", rule.mode()));
    }

    private Snapshot snapshotForUpdate(UUID organizationId, UUID customerId) {
        return jdbc.query("""
                select * from customer_deadline_snapshots
                where organization_id=:organizationId and customer_id=:customerId for update
                """, params(organizationId, customerId), (row, index) -> new Snapshot(
                row.getObject("cycle_id", UUID.class), row.getObject("rule_version_id", UUID.class),
                row.getObject("base_interaction_id", UUID.class), row.getTimestamp("computed_deadline").toInstant(),
                row.getBoolean("no_reply_extension_used"))).stream().findFirst().orElse(null);
    }

    private UUID ownershipCycleId(UUID organizationId, UUID customerId) {
        long cycle = ownershipCycleNumber(organizationId, customerId);
        return ownershipCycleId(organizationId, customerId, cycle);
    }

    private long ownershipCycleNumber(UUID organizationId, UUID customerId) {
        return jdbc.query("""
                select ownership_cycle from customer_ownership_states
                where organization_id=:organizationId and customer_id=:customerId
                """, params(organizationId, customerId),
                (row, index) -> row.getLong("ownership_cycle")).stream().findFirst().orElse(0L);
    }

    private UUID ownershipCycleId(UUID organizationId, UUID customerId, long cycle) {
        return UUID.nameUUIDFromBytes((organizationId + ":" + customerId + ":" + cycle)
                .getBytes(StandardCharsets.UTF_8));
    }

    private Instant latestAppliedOccurredAt(UUID organizationId, UUID customerId, UUID cycleId) {
        return jdbc.query("""
                select interaction.contacted_at
                from customer_deadline_events event
                join interactions interaction on interaction.id=event.interaction_id
                where event.organization_id=:organizationId and event.customer_id=:customerId
                  and event.cycle_id=:cycleId and event.applied=true
                order by interaction.contacted_at desc limit 1
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("customerId", customerId).addValue("cycleId", cycleId),
                (row, index) -> row.getTimestamp("contacted_at").toInstant())
                .stream().findFirst().orElse(null);
    }

    private boolean noReplyUsedInCycle(UUID organizationId, UUID customerId, UUID cycleId) {
        Long count = jdbc.queryForObject("""
                select count(*) from customer_deadline_events
                where organization_id=:organizationId and customer_id=:customerId and cycle_id=:cycleId
                  and event_type='OUTBOUND_NO_REPLY'
                  and reason in ('EFFECTIVE_CONTACT','NO_AB_CLASSIFICATION')
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("customerId", customerId).addValue("cycleId", cycleId), Long.class);
        return count != null && count > 0;
    }

    private CustomerClassLevel currentLevel(UUID organizationId, UUID customerId) {
        return jdbc.query("""
                select level from customer_classifications
                where organization_id=:organizationId and customer_id=:customerId
                order by revision desc limit 1
                """, params(organizationId, customerId),
                (row, index) -> CustomerClassLevel.valueOf(row.getString("level"))).stream().findFirst().orElse(null);
    }

    private Rule activeRule(UUID organizationId, Instant at) {
        return jdbc.query("""
                select * from market_deadline_rule_versions
                where organization_id=:organizationId and effective_from<=:at
                order by effective_from desc, version desc limit 1
                """, new MapSqlParameterSource().addValue("organizationId", organizationId)
                .addValue("at", sqlTimestamp(at)),
                this::rule).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("MARKET_DEADLINE_RULE_NOT_FOUND", "组织缺少期限规则"));
    }

    private Instant calculate(Rule rule, Instant base, CustomerClassLevel level) {
        return DeadlineCalculator.calculate(base, level, rule.peaks(), rule.pauses(),
                rule.aPeakHours(), rule.bPeakHours(), rule.aOffPeakHours(), rule.bOffPeakHours(),
                rule.weekendsCount());
    }

    private Timestamp sqlTimestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Rule rule(ResultSet row, int index) throws SQLException {
        return new Rule(row.getObject("id", UUID.class), row.getString("mode"),
                readRanges(row.getString("peak_periods")), readRanges(row.getString("pause_periods")),
                row.getInt("a_peak_hours"), row.getInt("b_peak_hours"),
                row.getInt("a_off_peak_hours"), row.getInt("b_off_peak_hours"),
                row.getBoolean("weekends_count"));
    }

    private MarketDtos.DeadlineRuleResponse ruleResponse(ResultSet row, int index) throws SQLException {
        return new MarketDtos.DeadlineRuleResponse(row.getObject("id", UUID.class), row.getInt("version"),
                row.getTimestamp("effective_from").toInstant(), row.getString("mode"),
                readRanges(row.getString("peak_periods")), readRanges(row.getString("pause_periods")),
                row.getInt("a_peak_hours"), row.getInt("b_peak_hours"), row.getInt("a_off_peak_hours"),
                row.getInt("b_off_peak_hours"), row.getBoolean("weekends_count"),
                row.getObject("created_by_membership_id", UUID.class), row.getTimestamp("created_at").toInstant());
    }

    private MarketDtos.DeadlineRuleResponse findRule(UUID organizationId, UUID id) {
        return jdbc.query("select * from market_deadline_rule_versions where organization_id=:organizationId and id=:id",
                new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("id", id),
                this::ruleResponse).stream().findFirst().orElseThrow();
    }

    private int hours(Integer value, int fallback) {
        int result = value == null ? fallback : value;
        if (result < 1 || result > 2160) throw ApiException.badRequest("DEADLINE_HOURS_INVALID", "期限小时数必须在 1 到 2160 之间");
        return result;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法序列化期限规则", exception); }
    }

    private List<MarketDtos.DateRange> readRanges(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法读取期限规则", exception); }
    }

    private MapSqlParameterSource params(UUID organizationId, UUID customerId) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("customerId", customerId);
    }

    public record Evaluation(boolean deadlineApplied, boolean validOwnershipAction, String reason, Instant deadline) {}
    private record Snapshot(UUID cycleId, UUID ruleId, UUID baseInteractionId,
                            Instant deadline, boolean noReplyExtensionUsed) {}
    private record Rule(UUID id, String mode, List<MarketDtos.DateRange> peaks,
                        List<MarketDtos.DateRange> pauses, int aPeakHours, int bPeakHours,
                        int aOffPeakHours, int bOffPeakHours, boolean weekendsCount) {}
}
