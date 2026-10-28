package com.yingge.cloud.market.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.domain.CustomerQuality;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.customerpool.application.CustomerPoolService;
import com.yingge.cloud.market.api.MarketDtos;
import com.yingge.cloud.market.domain.CustomerClassLevel;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;
import com.yingge.cloud.tag.infrastructure.TagEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class MarketClassificationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final CustomerService customers;
    private final AuditService audit;
    private final MarketDeadlineService deadlines;
    private final CustomerPoolService customerPool;
    private final NotificationService notifications;

    public MarketClassificationService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                       CurrentActorProvider actors, PermissionService permissions,
                                       CustomerService customers, AuditService audit,
                                       MarketDeadlineService deadlines, CustomerPoolService customerPool,
                                       NotificationService notifications) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.actors = actors;
        this.permissions = permissions;
        this.customers = customers;
        this.audit = audit;
        this.deadlines = deadlines;
        this.customerPool = customerPool;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public List<MarketDtos.ClassificationResponse> history(UUID customerId) {
        CurrentActor actor = actors.require();
        customers.requireVisibleEntity(customerId, actor);
        return jdbc.query("""
                select * from customer_classifications
                where organization_id=:organizationId and customer_id=:customerId
                order by revision desc
                """, params(actor.organizationId(), customerId), this::classification);
    }

    @Transactional(readOnly = true)
    public MarketDtos.ClassificationResponse current(UUID customerId) {
        CurrentActor actor = actors.require();
        customers.requireVisibleEntity(customerId, actor);
        return current(actor.organizationId(), customerId);
    }

    @Transactional(readOnly = true)
    public void requireQualityTagConsistent(UUID organizationId, UUID customerId,
                                            java.util.Collection<TagEntity> currentTags,
                                            java.util.Collection<TagEntity> selectedTags) {
        String taggedLevel = qualityLevel(currentTags);
        String structuredLevel = jdbc.query("""
                select level from customer_classifications
                where organization_id=:organizationId and customer_id=:customerId
                order by revision desc limit 1
                """, params(organizationId, customerId),
                (row, index) -> row.getString("level")).stream().findFirst().orElse(null);
        String current = structuredLevel == null ? taggedLevel : structuredLevel;
        String selected = qualityLevel(selectedTags);
        if (selected == null) return;
        if (current == null) {
            throw ApiException.conflict("STRUCTURED_CLASSIFICATION_REQUIRED",
                    "客户 A/B/C 等级必须提交结构化事实依据，不能在沟通记录中作为普通标签新增");
        }
        if (!current.equals(selected)) {
            throw ApiException.conflict("STRUCTURED_CLASSIFICATION_REQUIRED",
                    "客户 A/B/C 等级必须通过结构化分级接口修改，不能作为普通标签覆盖");
        }
    }

    private String qualityLevel(java.util.Collection<TagEntity> values) {
        return values.stream()
                .filter(tag -> CustomerQuality.CATEGORY.equals(tag.getCategory()))
                .map(TagEntity::getName)
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(name -> name.substring(0, 1))
                .findFirst().orElse(null);
    }

    @Transactional
    public MarketDtos.ClassificationResponse classify(UUID customerId, MarketDtos.ClassificationRequest request) {
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        customers.requireTimelineEditable(customer, actor);
        return append(actor, customer, request, actor.isAdmin() && TextNormalizer.optional(request.correctionReason()) != null
                ? "ADMIN_CORRECTION" : "STRUCTURED");
    }

    @Transactional(readOnly = true)
    public List<MarketDtos.ReviewQueueItem> reviewQueue(LocalDate month, boolean recommendedOnly) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        LocalDate targetMonth = month == null ? LocalDate.now(BUSINESS_ZONE).withDayOfMonth(1) : month.withDayOfMonth(1);
        return jdbc.query("""
                select classification.id classification_id, classification.customer_id, customer.full_name,
                       classification.level, classification.submitted_by_membership_id,
                       account.display_name submitted_by_display_name, classification.team_id_snapshot,
                       queue.recommendation_month, queue.recommended, queue.selected_at,
                       queue.review_status, classification.created_at
                from customer_classification_review_queue queue
                join customer_classifications classification on classification.id=queue.classification_id
                join customers customer on customer.id=classification.customer_id
                left join memberships membership on membership.id=classification.submitted_by_membership_id
                left join user_accounts account on account.id=membership.user_id
                where queue.organization_id=:organizationId
                  and queue.recommendation_month=:month
                  and (:recommendedOnly=false or queue.recommended=true or queue.selected_at is not null)
                order by queue.selected_at nulls last, md5(classification.id::text)
                """, new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId())
                .addValue("month", targetMonth)
                .addValue("recommendedOnly", recommendedOnly), (row, index) -> new MarketDtos.ReviewQueueItem(
                row.getObject("classification_id", UUID.class), row.getObject("customer_id", UUID.class),
                row.getString("full_name"), CustomerClassLevel.valueOf(row.getString("level")),
                row.getObject("submitted_by_membership_id", UUID.class), row.getString("submitted_by_display_name"),
                row.getObject("team_id_snapshot", UUID.class), row.getObject("recommendation_month", LocalDate.class),
                row.getBoolean("recommended"), row.getObject("selected_at") != null,
                row.getString("review_status"), row.getTimestamp("created_at").toInstant()));
    }

    @Transactional
    public void selectForReview(UUID classificationId) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        int updated = jdbc.update("""
                update customer_classification_review_queue
                set selected_at=coalesce(selected_at, now()), selected_by_membership_id=:membershipId
                where classification_id=:classificationId and organization_id=:organizationId
                  and recommended=true and review_status='PENDING'
                """, new MapSqlParameterSource()
                .addValue("classificationId", classificationId)
                .addValue("organizationId", actor.organizationId())
                .addValue("membershipId", actor.membershipId()));
        if (updated == 0) throw ApiException.conflict("CLASSIFICATION_REVIEW_NOT_RECOMMENDED", "该认定不在当前推荐抽查集中或已完成复核");
        audit.record(actor, "CUSTOMER_CLASSIFICATION_REVIEW_SELECTED", "CUSTOMER_CLASSIFICATION",
                classificationId.toString(), "SUCCESS", null);
    }

    @Transactional
    public MarketDtos.ClassificationResponse review(UUID classificationId, MarketDtos.ReviewRequest request) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        ClassificationTarget target = jdbc.query("""
                select classification.customer_id, queue.review_status, queue.selected_at
                from customer_classification_review_queue queue
                join customer_classifications classification on classification.id=queue.classification_id
                where queue.classification_id=:classificationId and queue.organization_id=:organizationId
                for update
                """, new MapSqlParameterSource()
                .addValue("classificationId", classificationId)
                .addValue("organizationId", actor.organizationId()),
                (rs, rowNum) -> new ClassificationTarget(rs.getObject("customer_id", UUID.class),
                        rs.getString("review_status"), rs.getObject("selected_at") != null))
                .stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("CLASSIFICATION_REVIEW_NOT_FOUND", "抽查记录不存在"));
        if (!"PENDING".equals(target.status())) {
            throw ApiException.conflict("CLASSIFICATION_REVIEW_ALREADY_DONE", "该抽查已经完成");
        }
        if (!target.selected()) {
            throw ApiException.conflict("CLASSIFICATION_REVIEW_NOT_SELECTED", "请先从推荐集中选择该客户，再提交复核结论");
        }
        MarketDtos.ClassificationResponse result = findById(actor.organizationId(), classificationId);
        if (request.decision() == MarketDtos.ReviewDecision.CORRECTED) {
            if (request.correction() == null || TextNormalizer.optional(request.note()) == null) {
                throw ApiException.badRequest("CLASSIFICATION_CORRECTION_REQUIRED", "纠正等级时必须提供完整结构化认定和纠正说明");
            }
            CustomerEntity customer = customers.requireVisibleEntity(target.customerId(), actor);
            MarketDtos.ClassificationRequest correction = new MarketDtos.ClassificationRequest(
                    request.correction().level(), request.correction().recognitionReason(),
                    request.correction().potentialDemand(), request.correction().aSignals(),
                    request.correction().evidenceInteractionIds(), request.note());
            result = append(actor, customer, correction, "ADMIN_CORRECTION");
        }
        jdbc.update("""
                update customer_classification_review_queue
                set review_status=:status, reviewed_at=now(), reviewed_by_membership_id=:membershipId,
                    result_classification_id=:resultId, review_note=:note
                where classification_id=:classificationId
                """, new MapSqlParameterSource()
                .addValue("status", request.decision().name())
                .addValue("membershipId", actor.membershipId())
                .addValue("resultId", result.id())
                .addValue("note", TextNormalizer.optional(request.note()))
                .addValue("classificationId", classificationId));
        audit.record(actor, "CUSTOMER_CLASSIFICATION_REVIEWED", "CUSTOMER_CLASSIFICATION",
                classificationId.toString(), "SUCCESS", Map.of("decision", request.decision().name()));
        return result;
    }

    @Transactional(readOnly = true)
    public List<MarketDtos.MemberAnomaly> anomalies() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        List<MemberRate> rates = jdbc.query("""
                select classification.submitted_by_membership_id membership_id,
                       account.display_name, classification.team_id_snapshot team_id,
                       count(*) total_decisions,
                       count(*) filter (where classification.level='A') a_decisions
                from customer_classifications classification
                join memberships membership on membership.id=classification.submitted_by_membership_id
                join user_accounts account on account.id=membership.user_id
                where classification.organization_id=:organizationId
                  and classification.source<>'LEGACY_IMPORT'
                  and classification.created_at >= now() - interval '30 days'
                group by classification.submitted_by_membership_id, account.display_name,
                         classification.team_id_snapshot
                order by account.display_name
                """, new MapSqlParameterSource("organizationId", actor.organizationId()),
                (row, index) -> new MemberRate(row.getObject("membership_id", UUID.class),
                        row.getString("display_name"), row.getObject("team_id", UUID.class),
                        row.getLong("total_decisions"), row.getLong("a_decisions")));
        Map<UUID, Double> medians = new LinkedHashMap<>();
        Set<UUID> teamIds = new LinkedHashSet<>();
        rates.forEach(rate -> teamIds.add(rate.teamId()));
        for (UUID teamId : teamIds) {
            List<Double> values = rates.stream().filter(rate -> java.util.Objects.equals(teamId, rate.teamId()))
                    .map(MemberRate::aRate).sorted().toList();
            medians.put(teamId, median(values));
        }
        return rates.stream().map(rate -> {
            double median = medians.getOrDefault(rate.teamId(), 0d);
            return new MarketDtos.MemberAnomaly(rate.membershipId(), rate.displayName(), rate.teamId(),
                    rate.total(), rate.aCount(), rate.aRate(), median,
                    rate.total() >= 10 && rate.aRate() > median * 2d);
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<MarketDtos.ClassificationDataIssue> dataIssues() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return jdbc.query("""
                with quality as (
                    select customer_tag.customer_id,
                           count(*) quality_count,
                           string_agg(tag.name, '、' order by tag.name) quality_tags,
                           min(substring(tag.name from 1 for 1)) single_level
                    from customer_tags customer_tag
                    join tags tag on tag.id=customer_tag.tag_id
                    where tag.organization_id=:organizationId and tag.category='客户质量'
                    group by customer_tag.customer_id
                ), latest as (
                    select distinct on (classification.customer_id)
                           classification.customer_id, classification.level
                    from customer_classifications classification
                    where classification.organization_id=:organizationId
                    order by classification.customer_id, classification.revision desc
                )
                select customer.id customer_id, customer.full_name,
                       case when quality.quality_count > 1 then 'MULTIPLE_QUALITY_TAGS'
                            else 'CLASSIFICATION_TAG_MISMATCH' end issue_type,
                       quality.quality_tags, latest.level structured_level
                from quality
                join customers customer on customer.id=quality.customer_id
                  and customer.organization_id=:organizationId
                left join latest on latest.customer_id=customer.id
                where quality.quality_count > 1
                   or (quality.quality_count=1 and latest.level is not null
                       and quality.single_level<>latest.level)
                order by customer.full_name, customer.id
                """, new MapSqlParameterSource("organizationId", actor.organizationId()),
                (row, index) -> new MarketDtos.ClassificationDataIssue(
                        row.getObject("customer_id", UUID.class), row.getString("full_name"),
                        row.getString("issue_type"), row.getString("quality_tags"),
                        row.getString("structured_level")));
    }

    private MarketDtos.ClassificationResponse append(CurrentActor actor, CustomerEntity customer,
                                                      MarketDtos.ClassificationRequest request, String source) {
        List<String> signals = normalizedStrings(request.aSignals());
        if (request.level() == CustomerClassLevel.A && signals.isEmpty()) {
            throw ApiException.badRequest("A_CLASS_SIGNAL_REQUIRED", "A 类认定至少填写一项近期推进信号");
        }
        Set<UUID> evidence = request.evidenceInteractionIds() == null
                ? Set.of() : new LinkedHashSet<>(request.evidenceInteractionIds());
        if (evidence.isEmpty()) {
            throw ApiException.badRequest("CLASSIFICATION_EVIDENCE_REQUIRED", "结构化分级至少关联一条客户时间线作为事实依据");
        }
        Long evidenceCount = jdbc.queryForObject("""
                select count(*) from interactions
                where organization_id=:organizationId and customer_id=:customerId and id in (:ids)
                """, new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId())
                .addValue("customerId", customer.getId()).addValue("ids", evidence), Long.class);
        if (evidenceCount == null || evidenceCount != evidence.size()) {
            throw ApiException.badRequest("CLASSIFICATION_EVIDENCE_INVALID", "事实依据必须全部来自当前客户的时间线");
        }
        CurrentRevision previous = jdbc.query("""
                select id, revision from customer_classifications
                where organization_id=:organizationId and customer_id=:customerId
                order by revision desc limit 1 for update
                """, params(actor.organizationId(), customer.getId()),
                (row, index) -> new CurrentRevision(row.getObject("id", UUID.class), row.getInt("revision")))
                .stream().findFirst().orElse(new CurrentRevision(null, 0));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into customer_classifications (
                        id, organization_id, customer_id, revision, level, recognition_reason,
                        potential_demand, a_signals, evidence_interaction_ids,
                        submitted_by_membership_id, team_id_snapshot, source, correction_reason,
                        previous_classification_id
                    ) values (
                        :id, :organizationId, :customerId, :revision, :level, :reason,
                        :potentialDemand, cast(:signals as jsonb), cast(:evidence as jsonb),
                        :membershipId, :teamId, :source, :correctionReason, :previousId
                    )
                    """, new MapSqlParameterSource()
                    .addValue("id", id).addValue("organizationId", actor.organizationId())
                    .addValue("customerId", customer.getId()).addValue("revision", previous.revision() + 1)
                    .addValue("level", request.level().name())
                    .addValue("reason", TextNormalizer.required(request.recognitionReason()))
                    .addValue("potentialDemand", TextNormalizer.required(request.potentialDemand()))
                    .addValue("signals", json(signals)).addValue("evidence", json(evidence))
                    .addValue("membershipId", actor.membershipId()).addValue("teamId", customer.getTeamId())
                    .addValue("source", source).addValue("correctionReason", TextNormalizer.optional(request.correctionReason()))
                    .addValue("previousId", previous.id()));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("CLASSIFICATION_CONCURRENT_UPDATE", "客户分级已被其他成员更新，请刷新后重试");
        }
        syncQualityTag(actor.organizationId(), customer.getId(), request.level());
        Instant classifiedAt = Instant.now();
        deadlines.recalculateForClassification(actor.organizationId(), customer.getId(), id,
                request.level(), classifiedAt);
        customerPool.reconcileClassification(actor.organizationId(), customer.getId(),
                request.level().name(), actor.membershipId());
        if (request.level() == CustomerClassLevel.A) {
            enqueueAndRecalculateRecommendations(actor.organizationId(), id);
        }
        List<UUID> recipients = classificationRecipients(actor.organizationId(), customer.getId());
        notifications.publish(actor.organizationId(), recipients, "CUSTOMER_QUALITY_CHANGED",
                "客户等级已更新", "客户等级已更新为 " + request.level().name() + " 类，请按最新规则处理。",
                "/customers/" + customer.getId(), "classification:" + id);
        audit.record(actor, "CUSTOMER_CLASSIFICATION_CREATED", "CUSTOMER", customer.getId().toString(), "SUCCESS",
                Map.of("classificationId", id.toString(), "level", request.level().name(), "revision", previous.revision() + 1));
        return findById(actor.organizationId(), id);
    }

    private void enqueueAndRecalculateRecommendations(UUID organizationId, UUID classificationId) {
        jdbc.update("""
                insert into customer_classification_review_queue (
                    classification_id, organization_id, recommendation_month
                )
                select id, organization_id,
                       date_trunc('month', created_at at time zone 'Asia/Shanghai')::date
                from customer_classifications where id=:classificationId
                on conflict (classification_id) do nothing
                """, new MapSqlParameterSource("classificationId", classificationId));
        jdbc.update("""
                with selected_period as (
                    select recommendation_month as period_start from customer_classification_review_queue
                    where classification_id=:classificationId
                ), ranked as (
                    select queue.classification_id,
                           row_number() over (
                               order by md5(queue.classification_id::text || queue.recommendation_month::text)
                           ) as row_number_value,
                           ceil(count(*) over () * 0.30)::integer as target_count
                    from customer_classification_review_queue queue, selected_period
                    where queue.organization_id=:organizationId
                      and queue.recommendation_month=selected_period.period_start
                )
                update customer_classification_review_queue queue
                set recommended=(ranked.row_number_value <= ranked.target_count)
                from ranked where queue.classification_id=ranked.classification_id
                """, new MapSqlParameterSource()
                .addValue("classificationId", classificationId).addValue("organizationId", organizationId));
    }

    private void syncQualityTag(UUID organizationId, UUID customerId, CustomerClassLevel level) {
        UUID tagId = jdbc.queryForObject("""
                select id from tags where organization_id=:organizationId
                  and category='客户质量' and name=:name and active=true
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId).addValue("name", level.name() + "类用户"), UUID.class);
        if (tagId == null) throw ApiException.conflict("QUALITY_TAG_NOT_FOUND", "系统客户质量标签缺失");
        jdbc.update("""
                delete from customer_tags customer_tag using tags tag
                where customer_tag.tag_id=tag.id and customer_tag.customer_id=:customerId
                  and tag.organization_id=:organizationId and tag.category='客户质量'
                """, params(organizationId, customerId));
        jdbc.update("insert into customer_tags (customer_id, tag_id) values (:customerId, :tagId) on conflict do nothing",
                new MapSqlParameterSource().addValue("customerId", customerId).addValue("tagId", tagId));
    }

    private List<UUID> classificationRecipients(UUID organizationId, UUID customerId) {
        return jdbc.queryForList("""
                select distinct recipient from (
                    select customer.owner_membership_id recipient
                    from customers customer
                    where customer.organization_id=:organizationId and customer.id=:customerId
                    union
                    select membership_role.membership_id recipient
                    from membership_roles membership_role
                    join roles role on role.id=membership_role.role_id and role.code='ORG_ADMIN'
                    join memberships membership on membership.id=membership_role.membership_id
                      and membership.organization_id=:organizationId and membership.status='ACTIVE'
                ) recipients where recipient is not null
                """, params(organizationId, customerId), UUID.class);
    }

    private MarketDtos.ClassificationResponse current(UUID organizationId, UUID customerId) {
        return jdbc.query("""
                select * from customer_classifications
                where organization_id=:organizationId and customer_id=:customerId
                order by revision desc limit 1
                """, params(organizationId, customerId), this::classification).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_CLASSIFICATION_NOT_FOUND", "客户尚未完成结构化分级"));
    }

    private MarketDtos.ClassificationResponse findById(UUID organizationId, UUID id) {
        return jdbc.query("select * from customer_classifications where organization_id=:organizationId and id=:id",
                new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("id", id),
                this::classification).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("CUSTOMER_CLASSIFICATION_NOT_FOUND", "客户分级不存在"));
    }

    private MarketDtos.ClassificationResponse classification(ResultSet row, int index) throws SQLException {
        return new MarketDtos.ClassificationResponse(
                row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class), row.getInt("revision"),
                CustomerClassLevel.valueOf(row.getString("level")), row.getString("recognition_reason"),
                row.getString("potential_demand"), read(row.getString("a_signals"), new TypeReference<>() {}),
                read(row.getString("evidence_interaction_ids"), new TypeReference<>() {}),
                row.getObject("submitted_by_membership_id", UUID.class), row.getObject("team_id_snapshot", UUID.class),
                row.getString("source"), row.getString("correction_reason"),
                row.getObject("previous_classification_id", UUID.class), row.getTimestamp("created_at").toInstant());
    }

    private MapSqlParameterSource params(UUID organizationId, UUID customerId) {
        return new MapSqlParameterSource().addValue("organizationId", organizationId).addValue("customerId", customerId);
    }

    private List<String> normalizedStrings(Set<String> values) {
        if (values == null) return List.of();
        List<String> result = new ArrayList<>();
        values.stream().map(TextNormalizer::optional).filter(java.util.Objects::nonNull).forEach(result::add);
        return List.copyOf(result);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法序列化市场规则数据", exception); }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("无法读取市场规则数据", exception); }
    }

    private double median(List<Double> values) {
        if (values.isEmpty()) return 0d;
        int middle = values.size() / 2;
        return values.size() % 2 == 1 ? values.get(middle) : (values.get(middle - 1) + values.get(middle)) / 2d;
    }

    private record CurrentRevision(UUID id, int revision) {}
    private record ClassificationTarget(UUID customerId, String status, boolean selected) {}
    private record MemberRate(UUID membershipId, String displayName, UUID teamId, long total, long aCount) {
        double aRate() { return total == 0 ? 0d : (double) aCount / total; }
    }
}
