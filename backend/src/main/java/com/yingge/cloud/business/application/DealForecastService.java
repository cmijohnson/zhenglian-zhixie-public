package com.yingge.cloud.business.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.business.api.DealForecastDtos;
import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.business.domain.DealForecastEvidenceType;
import com.yingge.cloud.business.domain.DealForecastOutcome;
import com.yingge.cloud.business.domain.DealForecastStatus;
import com.yingge.cloud.business.infrastructure.BusinessCycleEntity;
import com.yingge.cloud.business.infrastructure.BusinessCycleRepository;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.api.PageResponse;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DealForecastService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<BusinessCycleStatus> SUBMITTABLE = Set.of(
            BusinessCycleStatus.IN_PROGRESS, BusinessCycleStatus.LOCK_PENDING,
            BusinessCycleStatus.FORMALLY_QUOTED);
    private static final TypeReference<List<DealForecastEvidenceType>> EVIDENCE_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<UUID>> UUID_LIST_TYPE = new TypeReference<>() {};

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final CustomerService customers;
    private final BusinessCycleRepository cycles;
    private final BusinessMembershipDirectory membershipDirectory;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ObjectMapper json;

    public DealForecastService(NamedParameterJdbcTemplate jdbc, CurrentActorProvider actors,
                               PermissionService permissions, CustomerService customers,
                               BusinessCycleRepository cycles, BusinessMembershipDirectory membershipDirectory,
                               AuditService audit, NotificationService notifications, ObjectMapper json) {
        this.jdbc = jdbc;
        this.actors = actors;
        this.permissions = permissions;
        this.customers = customers;
        this.cycles = cycles;
        this.membershipDirectory = membershipDirectory;
        this.audit = audit;
        this.notifications = notifications;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<DealForecastDtos.Response> list(UUID customerId, UUID cycleId) {
        CurrentActor actor = actors.require();
        customers.requireVisibleEntity(customerId, actor);
        requireCycle(actor, customerId, cycleId);
        return queryResponses("forecast.customer_id=:customerId and forecast.business_cycle_id=:cycleId",
                params("organizationId", actor.organizationId(), "customerId", customerId, "cycleId", cycleId),
                "forecast.created_at desc", 100, 0);
    }

    @Transactional
    public DealForecastDtos.Response create(UUID customerId, UUID cycleId, DealForecastDtos.CreateRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = requireWritableActor();
        CustomerEntity customer = writableCustomer(actor, customerId);
        BusinessCycleEntity cycle = requireSubmittableCycle(actor, customerId, cycleId);
        ForecastValues values = validateValues(actor, customerId, body.evidenceTypes(),
                body.otherEvidenceDescription(), body.evidenceInteractionIds(), body.factSummary(),
                body.expectedCloseDate(), body.currentObstacle(), body.nextAction(), body.nextActionAt());
        String keyHash = hash(body.idempotencyKey());
        List<UUID> replay = jdbc.queryForList("""
                select id from customer_deal_forecasts
                where organization_id=:organizationId and submitted_by_membership_id=:membershipId
                  and idempotency_key_hash=:hash
                """, params("organizationId", actor.organizationId(), "membershipId", actor.membershipId(),
                "hash", keyHash), UUID.class);
        if (!replay.isEmpty()) {
            DealForecastDtos.Response existing = response(actor.organizationId(), replay.getFirst());
            if (!existing.customerId().equals(customerId) || !existing.businessCycleId().equals(cycleId)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已用于其他成交预测");
            }
            return existing;
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    insert into customer_deal_forecasts (
                      id,organization_id,customer_id,business_cycle_id,submitted_by_membership_id,
                      evidence_types,other_evidence_description,fact_summary,expected_close_date,
                      current_obstacle,next_action,next_action_at,idempotency_key_hash)
                    values (:id,:organizationId,:customerId,:cycleId,:membershipId,cast(:evidenceTypes as jsonb),
                      :otherEvidence,:factSummary,:expectedCloseDate,:currentObstacle,:nextAction,:nextActionAt,:hash)
                    """, params("id", id, "organizationId", actor.organizationId(), "customerId", customerId,
                    "cycleId", cycleId, "membershipId", actor.membershipId(),
                    "evidenceTypes", toJson(values.evidenceTypes()), "otherEvidence", values.otherEvidenceDescription(),
                    "factSummary", values.factSummary(), "expectedCloseDate", values.expectedCloseDate(),
                    "currentObstacle", values.currentObstacle(), "nextAction", values.nextAction(),
                    "nextActionAt", Timestamp.from(values.nextActionAt()), "hash", keyHash));
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("ACTIVE_DEAL_FORECAST_EXISTS", "当前业务周期已有一份有效的高意向成单申报");
        }
        replaceEvidence(id, values.evidenceInteractionIds());
        appendEvent(actor.organizationId(), id, actor.membershipId(), "SUBMITTED", null,
                DealForecastStatus.PENDING_REVIEW, Map.of("businessCycleId", cycleId.toString()));
        audit.record(actor, "DEAL_FORECAST_SUBMITTED", "DEAL_FORECAST", id.toString(), "SUCCESS",
                Map.of("customerId", customerId.toString(), "businessCycleId", cycleId.toString()));
        publish(actor, customer, cycle, id, "DEAL_FORECAST_PENDING", "高意向成单待确认",
                "一项有沟通依据的成交预测已提交，请管理员核对。", "deal-forecast-pending:" + id);
        return response(actor.organizationId(), id);
    }

    @Transactional
    public DealForecastDtos.Response update(UUID customerId, UUID cycleId, UUID forecastId,
                                             DealForecastDtos.UpdateRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = requireWritableActor();
        writableCustomer(actor, customerId);
        requireSubmittableCycle(actor, customerId, cycleId);
        ForecastValues values = validateValues(actor, customerId, body.evidenceTypes(),
                body.otherEvidenceDescription(), body.evidenceInteractionIds(), body.factSummary(),
                body.expectedCloseDate(), body.currentObstacle(), body.nextAction(), body.nextActionAt());
        Map<String, Object> locked = lockForecast(actor.organizationId(), customerId, cycleId, forecastId);
        DealForecastStatus previous = DealForecastStatus.valueOf((String) locked.get("status"));
        if (!Set.of(DealForecastStatus.PENDING_REVIEW, DealForecastStatus.RETURNED).contains(previous)) {
            throw ApiException.conflict("DEAL_FORECAST_NOT_EDITABLE", "当前成交预测不能修改");
        }
        int changed = jdbc.update("""
                update customer_deal_forecasts set evidence_types=cast(:evidenceTypes as jsonb),
                  other_evidence_description=:otherEvidence,fact_summary=:factSummary,
                  expected_close_date=:expectedCloseDate,current_obstacle=:currentObstacle,
                  next_action=:nextAction,next_action_at=:nextActionAt,status='PENDING_REVIEW',
                  review_note=null,reviewed_by_membership_id=null,reviewed_at=null,
                  updated_at=now(),version=version+1
                where id=:id and organization_id=:organizationId and version=:version
                """, params("evidenceTypes", toJson(values.evidenceTypes()),
                "otherEvidence", values.otherEvidenceDescription(), "factSummary", values.factSummary(),
                "expectedCloseDate", values.expectedCloseDate(), "currentObstacle", values.currentObstacle(),
                "nextAction", values.nextAction(), "nextActionAt", Timestamp.from(values.nextActionAt()), "id", forecastId,
                "organizationId", actor.organizationId(), "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        replaceEvidence(forecastId, values.evidenceInteractionIds());
        appendEvent(actor.organizationId(), forecastId, actor.membershipId(), "RESUBMITTED", previous,
                DealForecastStatus.PENDING_REVIEW, Map.of());
        audit.record(actor, "DEAL_FORECAST_RESUBMITTED", "DEAL_FORECAST", forecastId.toString(), "SUCCESS", null);
        DealForecastDtos.Response response = response(actor.organizationId(), forecastId);
        publish(actor, customers.requireVisibleEntity(customerId, actor),
                requireCycle(actor, customerId, cycleId), forecastId, "DEAL_FORECAST_PENDING",
                "高意向成单重新提交", "一项成交预测已补充事实并重新提交。",
                "deal-forecast-resubmitted:" + forecastId + ":" + response.version());
        return response;
    }

    @Transactional
    public DealForecastDtos.Response withdraw(UUID customerId, UUID cycleId, UUID forecastId,
                                               DealForecastDtos.WithdrawRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = requireWritableActor();
        writableCustomer(actor, customerId);
        requireCycle(actor, customerId, cycleId);
        Map<String, Object> locked = lockForecast(actor.organizationId(), customerId, cycleId, forecastId);
        DealForecastStatus previous = DealForecastStatus.valueOf((String) locked.get("status"));
        if (!Set.of(DealForecastStatus.PENDING_REVIEW, DealForecastStatus.RETURNED).contains(previous)) {
            throw ApiException.conflict("DEAL_FORECAST_NOT_WITHDRAWABLE", "当前成交预测不能撤回");
        }
        if (!actor.isAdmin() && !actor.membershipId().equals(locked.get("submitted_by_membership_id"))) {
            throw ApiException.forbidden("只有申报人或管理员可以撤回成交预测");
        }
        int changed = jdbc.update("""
                update customer_deal_forecasts set status='WITHDRAWN',review_note=:reason,
                  updated_at=now(),version=version+1 where id=:id and organization_id=:organizationId
                  and version=:version
                """, params("reason", TextNormalizer.required(body.reason()), "id", forecastId,
                "organizationId", actor.organizationId(), "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        appendEvent(actor.organizationId(), forecastId, actor.membershipId(), "WITHDRAWN", previous,
                DealForecastStatus.WITHDRAWN, Map.of("reason", TextNormalizer.required(body.reason())));
        audit.record(actor, "DEAL_FORECAST_WITHDRAWN", "DEAL_FORECAST", forecastId.toString(), "SUCCESS", null);
        return response(actor.organizationId(), forecastId);
    }

    @Transactional(readOnly = true)
    public PageResponse<DealForecastDtos.Response> search(
            DealForecastStatus status, UUID teamId, UUID ownerMembershipId, UUID submittedByMembershipId,
            LocalDate expectedFrom, LocalDate expectedTo, DealForecastEvidenceType evidenceType,
            String quality, Boolean crossSale, BusinessCycleStatus cycleStatus, int page, int size) {
        CurrentActor actor = requireManagerReader();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        List<String> clauses = new ArrayList<>();
        MapSqlParameterSource parameters = params("organizationId", actor.organizationId());
        if (status != null) { clauses.add("forecast.status=:status"); parameters.addValue("status", status.name()); }
        if (teamId != null) { clauses.add("customer.team_id=:teamId"); parameters.addValue("teamId", teamId); }
        if (ownerMembershipId != null) { clauses.add("customer.owner_membership_id=:ownerId"); parameters.addValue("ownerId", ownerMembershipId); }
        if (submittedByMembershipId != null) { clauses.add("forecast.submitted_by_membership_id=:submitterId"); parameters.addValue("submitterId", submittedByMembershipId); }
        if (expectedFrom != null) { clauses.add("forecast.expected_close_date>=:expectedFrom"); parameters.addValue("expectedFrom", expectedFrom); }
        if (expectedTo != null) { clauses.add("forecast.expected_close_date<=:expectedTo"); parameters.addValue("expectedTo", expectedTo); }
        if (evidenceType != null) { clauses.add("forecast.evidence_types @> cast(:evidenceType as jsonb)"); parameters.addValue("evidenceType", toJson(List.of(evidenceType))); }
        if (crossSale != null) clauses.add((crossSale ? "" : "not ") + "("
                + CrossSaleDefinition.ACTIVE_COLLABORATOR_EXISTS + ")");
        if (cycleStatus != null) { clauses.add("cycle.status=:cycleStatus"); parameters.addValue("cycleStatus", cycleStatus.name()); }
        String qualityLabel = qualityLabel(quality);
        if (qualityLabel != null) {
            clauses.add("exists(select 1 from customer_tags ct join tags tag on tag.id=ct.tag_id where ct.customer_id=customer.id and tag.organization_id=forecast.organization_id and tag.active=true and tag.category='客户质量' and tag.name=:qualityLabel)");
            parameters.addValue("qualityLabel", qualityLabel);
        }
        if (actor.isTeamManager()) {
            if (actor.managedTeamIds().isEmpty()) clauses.add("false");
            else { clauses.add("customer.team_id in (:managedTeamIds)"); parameters.addValue("managedTeamIds", actor.managedTeamIds()); }
        }
        String where = clauses.isEmpty() ? "true" : String.join(" and ", clauses);
        long total = jdbc.queryForObject("""
                select count(*) from customer_deal_forecasts forecast
                join customers customer on customer.id=forecast.customer_id and customer.organization_id=forecast.organization_id
                join customer_business_cycles cycle on cycle.id=forecast.business_cycle_id and cycle.organization_id=forecast.organization_id
                where forecast.organization_id=:organizationId and """ + " " + where, parameters, Long.class);
        List<DealForecastDtos.Response> items = queryResponses(where, parameters,
                "case when forecast.status='PENDING_REVIEW' then 0 when forecast.status='CONFIRMED' then 1 else 2 end, forecast.expected_close_date, forecast.updated_at desc",
                safeSize, safePage * safeSize);
        int totalPages = total == 0 ? 0 : (int) Math.ceil(total / (double) safeSize);
        return new PageResponse<>(items, safePage, safeSize, total, totalPages);
    }

    @Transactional(readOnly = true)
    public DealForecastDtos.Summary summary() {
        CurrentActor actor = requireManagerReader();
        String scope = actor.isTeamManager() ? " and customer.team_id in (:managedTeamIds)" : "";
        MapSqlParameterSource parameters = params("organizationId", actor.organizationId(),
                "managedTeamIds", actor.managedTeamIds().isEmpty() ? List.of(UUID.randomUUID()) : actor.managedTeamIds());
        return jdbc.queryForObject("""
                select count(*) filter (where forecast.status='PENDING_REVIEW') pending_review,
                  count(*) filter (where forecast.status='CONFIRMED') confirmed_active,
                  count(*) filter (where forecast.status='CONFIRMED' and forecast.expected_close_date between current_date and current_date+7) due_seven,
                  count(*) filter (where forecast.status in ('PENDING_REVIEW','CONFIRMED') and forecast.expected_close_date<current_date) overdue
                from customer_deal_forecasts forecast
                join customers customer on customer.id=forecast.customer_id and customer.organization_id=forecast.organization_id
                where forecast.organization_id=:organizationId""" + scope, parameters,
                (row, index) -> new DealForecastDtos.Summary(row.getLong("pending_review"),
                        row.getLong("confirmed_active"), row.getLong("due_seven"), row.getLong("overdue")));
    }

    @Transactional
    public DealForecastDtos.Response confirm(UUID forecastId, DealForecastDtos.ConfirmRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = requireAdmin();
        Map<String, Object> locked = lockForecast(actor.organizationId(), forecastId);
        if (!DealForecastStatus.PENDING_REVIEW.name().equals(locked.get("status"))) {
            throw ApiException.conflict("DEAL_FORECAST_NOT_PENDING", "该成交预测已不在待确认状态");
        }
        int changed = jdbc.update("""
                update customer_deal_forecasts set status='CONFIRMED',review_note=null,
                  reviewed_by_membership_id=:actor,reviewed_at=now(),updated_at=now(),version=version+1
                where id=:id and organization_id=:organizationId and version=:version
                """, params("actor", actor.membershipId(), "id", forecastId,
                "organizationId", actor.organizationId(), "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        UUID customerId = (UUID) locked.get("customer_id");
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        customers.syncHighIntentionFromForecast(customer, actor, forecastId);
        appendEvent(actor.organizationId(), forecastId, actor.membershipId(), "CONFIRMED",
                DealForecastStatus.PENDING_REVIEW, DealForecastStatus.CONFIRMED, Map.of());
        audit.record(actor, "DEAL_FORECAST_CONFIRMED", "DEAL_FORECAST", forecastId.toString(), "SUCCESS",
                Map.of("customerId", customerId.toString()));
        BusinessCycleEntity cycle = requireCycle(actor, customerId, (UUID) locked.get("business_cycle_id"));
        publish(actor, customer, cycle, forecastId, "DEAL_FORECAST_CONFIRMED", "高意向成单已确认",
                "管理员已确认一项成交预测，请按计划继续推进。", "deal-forecast-confirmed:" + forecastId);
        return response(actor.organizationId(), forecastId);
    }

    @Transactional
    public DealForecastDtos.Response returnForSupplement(UUID forecastId, DealForecastDtos.ReturnRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = requireAdmin();
        Map<String, Object> locked = lockForecast(actor.organizationId(), forecastId);
        if (!DealForecastStatus.PENDING_REVIEW.name().equals(locked.get("status"))) {
            throw ApiException.conflict("DEAL_FORECAST_NOT_PENDING", "该成交预测已不在待确认状态");
        }
        String reason = TextNormalizer.required(body.reason());
        int changed = jdbc.update("""
                update customer_deal_forecasts set status='RETURNED',review_note=:reason,
                  reviewed_by_membership_id=:actor,reviewed_at=now(),updated_at=now(),version=version+1
                where id=:id and organization_id=:organizationId and version=:version
                """, params("reason", reason, "actor", actor.membershipId(), "id", forecastId,
                "organizationId", actor.organizationId(), "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        appendEvent(actor.organizationId(), forecastId, actor.membershipId(), "RETURNED",
                DealForecastStatus.PENDING_REVIEW, DealForecastStatus.RETURNED, Map.of("reason", reason));
        audit.record(actor, "DEAL_FORECAST_RETURNED", "DEAL_FORECAST", forecastId.toString(), "SUCCESS",
                Map.of("reason", reason));
        DealForecastDtos.Response forecast = response(actor.organizationId(), forecastId);
        notifications.publish(actor.organizationId(), List.of(forecast.submittedByMembershipId()),
                "DEAL_FORECAST_RETURNED", "高意向成单需补充",
                "管理员已退回一项成交预测，请补充可核验事实。", "/customers/" + forecast.customerId(),
                "deal-forecast-returned:" + forecastId + ":" + forecast.version());
        return forecast;
    }

    @Transactional
    public void closeForCycle(UUID organizationId, UUID cycleId, DealForecastOutcome outcome,
                              UUID actorMembershipId) {
        if (LegacyDealFeatures.retired()) return;
        List<Map<String, Object>> active = jdbc.queryForList("""
                select id,status from customer_deal_forecasts where organization_id=:organizationId
                  and business_cycle_id=:cycleId and status in ('PENDING_REVIEW','CONFIRMED','RETURNED')
                for update
                """, params("organizationId", organizationId, "cycleId", cycleId));
        for (Map<String, Object> row : active) {
            UUID id = (UUID) row.get("id");
            DealForecastStatus previous = DealForecastStatus.valueOf((String) row.get("status"));
            jdbc.update("""
                    update customer_deal_forecasts set status='CLOSED',outcome=:outcome,closed_at=now(),
                      updated_at=now(),version=version+1 where id=:id
                    """, params("outcome", outcome.name(), "id", id));
            appendEvent(organizationId, id, actorMembershipId, "CLOSED_" + outcome.name(), previous,
                    DealForecastStatus.CLOSED, Map.of("outcome", outcome.name()));
        }
    }

    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Shanghai")
    @Transactional
    public void notifyOverdueForecasts() {
        if (LegacyDealFeatures.retired()) return;
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        List<Map<String, Object>> overdue = jdbc.queryForList("""
                select forecast.id,forecast.organization_id,forecast.customer_id,
                       forecast.submitted_by_membership_id,customer.owner_membership_id
                from customer_deal_forecasts forecast
                join customers customer on customer.id=forecast.customer_id
                where forecast.status in ('PENDING_REVIEW','CONFIRMED')
                  and forecast.expected_close_date<current_date and customer.archived_at is null
                """, Map.of());
        for (Map<String, Object> row : overdue) {
            UUID organizationId = (UUID) row.get("organization_id");
            LinkedHashSet<UUID> recipients = new LinkedHashSet<>();
            recipients.add((UUID) row.get("submitted_by_membership_id"));
            if (row.get("owner_membership_id") != null) recipients.add((UUID) row.get("owner_membership_id"));
            notifications.publish(organizationId, recipients, "DEAL_FORECAST_OVERDUE",
                    "成交预测已超过预计日期", "一项已申报的成交预测超过预计日期，请核对进展。",
                    "/customers/" + row.get("customer_id"),
                    "deal-forecast-overdue:" + row.get("id") + ":" + today);
        }
    }

    private ForecastValues validateValues(CurrentActor actor, UUID customerId,
                                          List<DealForecastEvidenceType> requestedTypes,
                                          String otherDescription, List<UUID> interactionIds,
                                          String factSummary, LocalDate expectedCloseDate,
                                          String obstacle, String nextAction, Instant nextActionAt) {
        LinkedHashSet<DealForecastEvidenceType> types = new LinkedHashSet<>(requestedTypes == null ? List.of() : requestedTypes);
        if (types.isEmpty() || requestedTypes == null || types.size() != requestedTypes.size()) {
            throw ApiException.badRequest("DEAL_FORECAST_EVIDENCE_TYPE_INVALID", "成交依据不能为空且不能重复");
        }
        String other = TextNormalizer.optional(otherDescription);
        if (types.contains(DealForecastEvidenceType.OTHER_VERIFIABLE_FACT) && other == null) {
            throw ApiException.badRequest("DEAL_FORECAST_OTHER_EVIDENCE_REQUIRED", "选择其他可核验事实时必须填写说明");
        }
        LinkedHashSet<UUID> evidence = new LinkedHashSet<>(interactionIds == null ? List.of() : interactionIds);
        if (evidence.isEmpty() || interactionIds == null || evidence.size() != interactionIds.size()) {
            throw ApiException.badRequest("DEAL_FORECAST_INTERACTION_INVALID", "至少关联一条且不能重复选择沟通记录");
        }
        Integer matched = jdbc.queryForObject("""
                select count(*) from interactions where organization_id=:organizationId
                  and customer_id=:customerId and id in (:ids)
                """, params("organizationId", actor.organizationId(), "customerId", customerId, "ids", evidence),
                Integer.class);
        if (matched == null || matched != evidence.size()) {
            throw ApiException.badRequest("DEAL_FORECAST_INTERACTION_INVALID", "包含不属于当前客户的沟通记录");
        }
        if (expectedCloseDate == null || expectedCloseDate.isBefore(LocalDate.now(BUSINESS_ZONE))) {
            throw ApiException.badRequest("DEAL_FORECAST_DATE_IN_PAST", "预计成单日期不能早于今天");
        }
        if (nextActionAt == null || nextActionAt.isBefore(Instant.now())) {
            throw ApiException.badRequest("DEAL_FORECAST_NEXT_ACTION_IN_PAST", "下一步行动时间不能早于当前时间");
        }
        return new ForecastValues(List.copyOf(types), other, List.copyOf(evidence),
                TextNormalizer.required(factSummary), expectedCloseDate, TextNormalizer.optional(obstacle),
                TextNormalizer.required(nextAction), nextActionAt);
    }

    private CustomerEntity writableCustomer(CurrentActor actor, UUID customerId) {
        permissions.requireCustomerWrite(actor);
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        customers.requireTimelineEditable(customer, actor);
        if (customer.getArchivedAt() != null) {
            throw ApiException.conflict("ARCHIVED_CUSTOMER_DEAL_FORECAST_FORBIDDEN", "归档客户不能提交成交预测");
        }
        return customer;
    }

    private BusinessCycleEntity requireSubmittableCycle(CurrentActor actor, UUID customerId, UUID cycleId) {
        BusinessCycleEntity cycle = requireCycle(actor, customerId, cycleId);
        if (!SUBMITTABLE.contains(cycle.getStatus())) {
            throw ApiException.conflict("DEAL_FORECAST_CYCLE_NOT_ACTIVE", "当前业务周期不能提交成交预测");
        }
        return cycle;
    }

    private BusinessCycleEntity requireCycle(CurrentActor actor, UUID customerId, UUID cycleId) {
        BusinessCycleEntity cycle = cycles.findByIdAndOrganizationId(cycleId, actor.organizationId())
                .orElseThrow(() -> ApiException.notFound("BUSINESS_CYCLE_NOT_FOUND", "业务周期不存在"));
        if (!cycle.getCustomerId().equals(customerId)) {
            throw ApiException.notFound("BUSINESS_CYCLE_NOT_FOUND", "业务周期不存在");
        }
        return cycle;
    }

    private CurrentActor requireWritableActor() {
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        return actor;
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return actor;
    }

    private CurrentActor requireManagerReader() {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin() && !actor.isTeamManager()) {
            throw ApiException.forbidden("只有管理员或团队长可以查看成交预测汇总");
        }
        return actor;
    }

    private Map<String, Object> lockForecast(UUID organizationId, UUID forecastId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select * from customer_deal_forecasts where id=:id and organization_id=:organizationId for update
                """, params("id", forecastId, "organizationId", organizationId));
        if (rows.isEmpty()) throw ApiException.notFound("DEAL_FORECAST_NOT_FOUND", "成交预测不存在");
        return rows.getFirst();
    }

    private Map<String, Object> lockForecast(UUID organizationId, UUID customerId, UUID cycleId, UUID forecastId) {
        Map<String, Object> row = lockForecast(organizationId, forecastId);
        if (!customerId.equals(row.get("customer_id")) || !cycleId.equals(row.get("business_cycle_id"))) {
            throw ApiException.notFound("DEAL_FORECAST_NOT_FOUND", "成交预测不存在");
        }
        return row;
    }

    private void replaceEvidence(UUID forecastId, List<UUID> evidenceIds) {
        jdbc.update("delete from customer_deal_forecast_evidence where forecast_id=:id", params("id", forecastId));
        for (UUID interactionId : evidenceIds) {
            jdbc.update("""
                    insert into customer_deal_forecast_evidence(forecast_id,interaction_id) values (:forecastId,:interactionId)
                    """, params("forecastId", forecastId, "interactionId", interactionId));
        }
    }

    private DealForecastDtos.Response response(UUID organizationId, UUID id) {
        List<DealForecastDtos.Response> rows = queryResponses("forecast.id=:id",
                params("organizationId", organizationId, "id", id), "forecast.created_at desc", 1, 0);
        if (rows.isEmpty()) throw ApiException.notFound("DEAL_FORECAST_NOT_FOUND", "成交预测不存在");
        return rows.getFirst();
    }

    private List<DealForecastDtos.Response> queryResponses(String clause, MapSqlParameterSource parameters,
                                                           String order, int limit, int offset) {
        parameters.addValue("limit", limit).addValue("offset", offset);
        String sql = """
                select forecast.*,customer.full_name customer_name,customer.owner_membership_id,customer.team_id,
                  cycle.cycle_number,cycle.title cycle_title,cycle.status cycle_status,
                  case when
                """ + CrossSaleDefinition.ACTIVE_COLLABORATOR_EXISTS + """
                  then true else false end cross_sale,
                  owner_account.display_name owner_name,team.name team_name,
                  submitter_account.display_name submitter_name,reviewer_account.display_name reviewer_name,
                  coalesce((select jsonb_agg(evidence.interaction_id order by evidence.linked_at)
                    from customer_deal_forecast_evidence evidence where evidence.forecast_id=forecast.id),'[]'::jsonb)::text evidence_ids
                from customer_deal_forecasts forecast
                join customers customer on customer.id=forecast.customer_id and customer.organization_id=forecast.organization_id
                join customer_business_cycles cycle on cycle.id=forecast.business_cycle_id and cycle.organization_id=forecast.organization_id
                join memberships submitter on submitter.id=forecast.submitted_by_membership_id
                join user_accounts submitter_account on submitter_account.id=submitter.user_id
                left join memberships owner on owner.id=customer.owner_membership_id
                left join user_accounts owner_account on owner_account.id=owner.user_id
                left join teams team on team.id=customer.team_id
                left join memberships reviewer on reviewer.id=forecast.reviewed_by_membership_id
                left join user_accounts reviewer_account on reviewer_account.id=reviewer.user_id
                where forecast.organization_id=:organizationId and
                """ + clause + " order by " + order + " limit :limit offset :offset";
        return jdbc.query(sql, parameters, this::mapResponse);
    }

    private DealForecastDtos.Response mapResponse(ResultSet row, int index) throws SQLException {
        List<DealForecastEvidenceType> types = read(row.getString("evidence_types"), EVIDENCE_TYPE);
        List<UUID> evidenceIds = read(row.getString("evidence_ids"), UUID_LIST_TYPE);
        LocalDate expected = row.getObject("expected_close_date", LocalDate.class);
        return new DealForecastDtos.Response(
                row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class), row.getString("customer_name"),
                row.getObject("business_cycle_id", UUID.class), row.getInt("cycle_number"), row.getString("cycle_title"),
                BusinessCycleStatus.valueOf(row.getString("cycle_status")), row.getBoolean("cross_sale"),
                row.getObject("owner_membership_id", UUID.class), row.getString("owner_name"),
                row.getObject("team_id", UUID.class), row.getString("team_name"),
                row.getObject("submitted_by_membership_id", UUID.class), row.getString("submitter_name"),
                types, row.getString("other_evidence_description"), evidenceIds, evidenceIds.size(),
                row.getString("fact_summary"), expected, expected.isBefore(LocalDate.now(BUSINESS_ZONE)),
                row.getString("current_obstacle"), row.getString("next_action"),
                row.getTimestamp("next_action_at").toInstant(), DealForecastStatus.valueOf(row.getString("status")),
                row.getString("review_note"), row.getObject("reviewed_by_membership_id", UUID.class),
                row.getString("reviewer_name"), instant(row, "reviewed_at"),
                row.getString("outcome") == null ? null : DealForecastOutcome.valueOf(row.getString("outcome")),
                instant(row, "closed_at"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), row.getLong("version"));
    }

    private void appendEvent(UUID organizationId, UUID forecastId, UUID actorMembershipId, String event,
                             DealForecastStatus previous, DealForecastStatus next, Map<String, Object> details) {
        jdbc.update("""
                insert into customer_deal_forecast_events
                  (id,organization_id,forecast_id,actor_membership_id,event_type,previous_status,new_status,details)
                values (:id,:organizationId,:forecastId,:actor,:event,:previous,:next,cast(:details as jsonb))
                """, params("id", UUID.randomUUID(), "organizationId", organizationId, "forecastId", forecastId,
                "actor", actorMembershipId, "event", event, "previous", previous == null ? null : previous.name(),
                "next", next.name(), "details", toJson(details)));
    }

    private void publish(CurrentActor actor, CustomerEntity customer, BusinessCycleEntity cycle, UUID forecastId,
                         String type, String title, String summary, String key) {
        notifications.publish(actor.organizationId(), membershipDirectory.notificationRecipients(
                        actor.organizationId(), customer.getId(), List.of(actor.membershipId())),
                type, title, summary, "/customers/" + customer.getId(), key);
    }

    private String qualityLabel(String code) {
        if (code == null || code.isBlank()) return null;
        return switch (code.trim().toUpperCase()) {
            case "A", "A类用户" -> "A类用户";
            case "B", "B类用户" -> "B类用户";
            case "C", "C类用户" -> "C类用户";
            default -> throw ApiException.badRequest("INVALID_CUSTOMER_QUALITY", "客户等级只能为 A、B 或 C");
        };
    }

    private String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(TextNormalizer.required(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Invalid deal forecast JSON", exception); }
    }

    private <T> T read(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception exception) { throw new IllegalStateException("Invalid deal forecast data", exception); }
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        return row.getTimestamp(column) == null ? null : row.getTimestamp(column).toInstant();
    }

    private ApiException stale() {
        return ApiException.conflict("STALE_VERSION", "成交预测已变化，请刷新后重试");
    }

    private MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource result = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) {
            result.addValue((String) values[index], values[index + 1]);
        }
        return result;
    }

    private record ForecastValues(List<DealForecastEvidenceType> evidenceTypes,
                                  String otherEvidenceDescription,
                                  List<UUID> evidenceInteractionIds,
                                  String factSummary,
                                  LocalDate expectedCloseDate,
                                  String currentObstacle,
                                  String nextAction,
                                  Instant nextActionAt) {}
}
