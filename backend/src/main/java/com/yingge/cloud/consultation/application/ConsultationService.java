package com.yingge.cloud.consultation.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.assistant.api.AssistantDtos;
import com.yingge.cloud.assistant.application.AssistantFacade;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.consultation.api.ConsultationDtos;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.followup.application.CustomerFollowUpService;
import com.yingge.cloud.interaction.api.InteractionDtos;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ConsultationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final CustomerService customers;
    private final InteractionService interactions;
    private final CustomerFollowUpService followUps;
    private final AuditService audit;
    private final AssistantFacade assistant;

    public ConsultationService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                               CurrentActorProvider actors, PermissionService permissions,
                               CustomerService customers, InteractionService interactions,
                               CustomerFollowUpService followUps, AuditService audit, AssistantFacade assistant) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.actors = actors;
        this.permissions = permissions;
        this.customers = customers;
        this.interactions = interactions;
        this.followUps = followUps;
        this.audit = audit;
        this.assistant = assistant;
    }

    @Transactional(readOnly = true)
    public List<ConsultationDtos.Response> list() {
        CurrentActor actor = actors.require();
        String scope = actor.isAdmin() ? "" : """
                and (handoff.created_by_membership_id=:membershipId
                  or handoff.consultant_membership_id=:membershipId
                  or customer.owner_membership_id=:membershipId
                  or exists(select 1 from customer_access_grants grant_row
                    where grant_row.customer_id=customer.id and grant_row.grantee_membership_id=:membershipId)
                  or (:teamManager and customer.team_id in (:managedTeamIds)))
                """;
        Set<UUID> managedTeams = actor.managedTeamIds().isEmpty()
                ? Set.of(UUID.fromString("00000000-0000-0000-0000-000000000000"))
                : actor.managedTeamIds();
        return jdbc.query(baseSelect() + " where handoff.organization_id=:organizationId " + scope
                        + " order by handoff.scheduled_start desc limit 300",
                params("organizationId", actor.organizationId(), "membershipId", actor.membershipId(),
                        "teamManager", actor.isTeamManager(), "managedTeamIds", managedTeams), this::map);
    }

    @Transactional(readOnly = true)
    public ConsultationDtos.Response get(UUID id) {
        CurrentActor actor = actors.require();
        ConsultationDtos.Response response = require(id, actor.organizationId());
        customers.requireVisibleEntity(response.customerId(), actor);
        return response;
    }

    @Transactional
    public AssistantDtos.ConsultationDraft draft(UUID customerId) {
        CurrentActor actor = actors.require();
        var customer = customers.requireVisibleEntity(customerId, actor);
        customers.requireTimelineEditable(customer, actor);
        return assistant.consultationDraft(customerId);
    }

    @Transactional
    public ConsultationDtos.Response create(ConsultationDtos.CreateRequest body) {
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        var customer = customers.requireVisibleEntity(body.customerId(), actor);
        customers.requireTimelineEditable(customer, actor);
        validateConsent(body.decisionParticipants(), body.decisionParticipantConsent());
        validateConsultant(actor.organizationId(), body.consultantMembershipId());
        validateSources(actor.organizationId(), body.customerId(), body.sourceInteractionIds());
        validateSchedule(actor.organizationId(), body.consultantMembershipId(), body.scheduledStart(), body.scheduledEnd(), null);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into consultation_handoffs (
                  id, organization_id, customer_id, customer_snapshot, created_by_membership_id, consultant_membership_id,
                  source_interaction_ids, consultation_reason, customer_quotes, goals_motivation, actions_taken,
                  main_concerns, decision_participants, decision_participant_consent, product_fit,
                  suggested_focus, explicit_taboos, expected_result, scheduled_start, scheduled_end)
                values (:id, :organizationId, :customerId, cast(:customerSnapshot as jsonb), :creatorId, :consultantId, cast(:sourceIds as jsonb),
                  :consultationReason, :customerQuotes, :goalsMotivation, :actionsTaken, :mainConcerns,
                  :decisionParticipants, :decisionConsent, :productFit, :suggestedFocus, :explicitTaboos,
                  :expectedResult, :scheduledStart, :scheduledEnd)
                """, params("id", id, "organizationId", actor.organizationId(), "customerId", body.customerId(),
                "customerSnapshot", json(Map.of(
                        "fullName", customer.getFullName(),
                        "school", customer.getSchool() == null ? "" : customer.getSchool(),
                        "major", customer.getMajor() == null ? "" : customer.getMajor(),
                        "grade", customer.getGrade() == null ? "" : customer.getGrade(),
                        "highSchool", customer.getHighSchool() == null ? "" : customer.getHighSchool(),
                        "sourceLocation", customer.getSourceLocation() == null ? "" : customer.getSourceLocation(),
                        "demandSummary", customer.getDemandSummary() == null ? "" : customer.getDemandSummary(),
                        "ownerMembershipId", customer.getOwnerMembershipId().toString(),
                        "capturedAt", Instant.now().toString())),
                "creatorId", actor.membershipId(), "consultantId", body.consultantMembershipId(),
                "sourceIds", json(body.sourceInteractionIds() == null ? List.of() : body.sourceInteractionIds()),
                "consultationReason", clean(body.consultationReason()), "customerQuotes", clean(body.customerQuotes()),
                "goalsMotivation", clean(body.goalsMotivation()), "actionsTaken", clean(body.actionsTaken()),
                "mainConcerns", clean(body.mainConcerns()),
                "decisionParticipants", TextNormalizer.optional(body.decisionParticipants()),
                "decisionConsent", body.decisionParticipantConsent(), "productFit", clean(body.productFit()),
                "suggestedFocus", clean(body.suggestedFocus()), "explicitTaboos", clean(body.explicitTaboos()),
                "expectedResult", clean(body.expectedResult()), "scheduledStart", timestamp(body.scheduledStart()),
                "scheduledEnd", timestamp(body.scheduledEnd())));
        audit.record(actor, "CONSULTATION_HANDOFF_CREATED", "CONSULTATION", id.toString(), "SUCCESS",
                Map.of("customerId", body.customerId().toString()));
        return require(id, actor.organizationId());
    }

    @Transactional
    public ConsultationDtos.Response accept(UUID id, ConsultationDtos.VersionRequest body) {
        CurrentActor actor = actors.require();
        ConsultationDtos.Response current = require(id, actor.organizationId());
        if (!current.consultantMembershipId().equals(actor.membershipId()) && !actor.isAdmin()) {
            throw ApiException.forbidden("只有指定顾问可以接受交接");
        }
        validateSchedule(actor.organizationId(), current.consultantMembershipId(), current.scheduledStart(),
                current.scheduledEnd(), id);
        int changed = jdbc.update("""
                update consultation_handoffs set status='SCHEDULED', accepted_at=now(),
                  updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId and status='PENDING_ACCEPTANCE' and version=:version
                """, params("id", id, "organizationId", actor.organizationId(), "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        jdbc.update("""
                insert into customer_access_grants
                  (id, organization_id, customer_id, grantee_membership_id, can_edit, granted_by_membership_id)
                values (:id, :organizationId, :customerId, :membershipId, true, :grantedBy)
                on conflict (organization_id, customer_id, grantee_membership_id)
                  where grantee_membership_id is not null do update set can_edit=true
                """, params("id", UUID.randomUUID(), "organizationId", actor.organizationId(),
                "customerId", current.customerId(), "membershipId", current.consultantMembershipId(),
                "grantedBy", actor.membershipId()));
        followUps.reconcileCustomer(actor.organizationId(), current.customerId());
        audit.record(actor, "CONSULTATION_HANDOFF_ACCEPTED", "CONSULTATION", id.toString(), "SUCCESS", null);
        return require(id, actor.organizationId());
    }

    @Transactional
    public ConsultationDtos.Response reject(UUID id, ConsultationDtos.RejectRequest body) {
        CurrentActor actor = actors.require();
        ConsultationDtos.Response current = require(id, actor.organizationId());
        if (!current.consultantMembershipId().equals(actor.membershipId()) && !actor.isAdmin()) {
            throw ApiException.forbidden("只有指定顾问可以拒绝交接");
        }
        int changed = jdbc.update("""
                update consultation_handoffs set status='REJECTED', rejection_reason=:reason,
                  updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId and status='PENDING_ACCEPTANCE' and version=:version
                """, params("reason", clean(body.reason()), "id", id, "organizationId", actor.organizationId(),
                "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        audit.record(actor, "CONSULTATION_HANDOFF_REJECTED", "CONSULTATION", id.toString(), "SUCCESS", null);
        return require(id, actor.organizationId());
    }

    @Transactional
    public ConsultationDtos.Response cancel(UUID id, ConsultationDtos.CancelRequest body) {
        CurrentActor actor = actors.require();
        ConsultationDtos.Response current = require(id, actor.organizationId());
        boolean allowed = actor.isAdmin() || current.createdByMembershipId().equals(actor.membershipId())
                || current.consultantMembershipId().equals(actor.membershipId());
        if (!allowed) throw ApiException.forbidden("只有发起人、顾问或管理员可以取消咨询");
        int changed = jdbc.update("""
                update consultation_handoffs set status='CANCELLED', rejection_reason=:reason,
                  cancelled_at=now(), updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId
                  and status in ('PENDING_ACCEPTANCE','SCHEDULED') and version=:version
                """, params("reason", clean(body.reason()), "id", id,
                "organizationId", actor.organizationId(), "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        audit.record(actor, "CONSULTATION_CANCELLED", "CONSULTATION", id.toString(), "SUCCESS", null);
        return require(id, actor.organizationId());
    }

    @Transactional
    public ConsultationDtos.Response end(UUID id, ConsultationDtos.EndRequest body) {
        CurrentActor actor = actors.require();
        ConsultationDtos.Response current = require(id, actor.organizationId());
        requireConsultantOrAdmin(current, actor);
        Instant endedAt = body.actualEndedAt() == null ? Instant.now() : body.actualEndedAt();
        if (endedAt.isAfter(Instant.now())) {
            throw ApiException.badRequest("CONSULTATION_END_TIME_IN_FUTURE", "咨询实际结束时间不能晚于系统当前时间");
        }
        int dueHours = settings(actor.organizationId()).feedbackDueHours();
        int changed = jdbc.update("""
                update consultation_handoffs set status='AWAITING_FEEDBACK', actual_ended_at=:endedAt,
                  feedback_due_at=:feedbackDueAt, updated_at=now(), version=version+1
                where id=:id and organization_id=:organizationId and status='SCHEDULED' and version=:version
                """, params("endedAt", timestamp(endedAt),
                "feedbackDueAt", timestamp(endedAt.plus(Duration.ofHours(dueHours))),
                "id", id, "organizationId", actor.organizationId(), "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        audit.record(actor, "CONSULTATION_ENDED", "CONSULTATION", id.toString(), "SUCCESS", null);
        return require(id, actor.organizationId());
    }

    @Transactional
    public ConsultationDtos.Response complete(UUID id, ConsultationDtos.CompleteRequest body) {
        CurrentActor actor = actors.require();
        ConsultationDtos.Response current = require(id, actor.organizationId());
        requireConsultantOrAdmin(current, actor);
        if (!Set.of("SCHEDULED", "AWAITING_FEEDBACK").contains(current.status())) {
            throw ApiException.conflict("CONSULTATION_NOT_AWAITING_RESULT", "当前咨询不能回填结果");
        }
        validateResult(actor.organizationId(), body);
        Instant completedAt = current.actualEndedAt() == null ? Instant.now() : current.actualEndedAt();
        int dueHours = settings(actor.organizationId()).feedbackDueHours();
        InteractionDtos.Response interaction = interactions.create(current.customerId(), new InteractionDtos.CreateRequest(
                completedAt,
                "咨询交接", "咨询结果回填", clean(body.resultSummary()),
                body.continueFollowUp() ? "继续推进" : "暂不推进", clean(body.nextStep()),
                body.nextFollowUpAt(), null, null, null, null));
        int changed = jdbc.update("""
                update consultation_handoffs set status='COMPLETED', result_summary=:resultSummary,
                  continue_follow_up=:continueFollowUp, next_step=:nextStep,
                  next_owner_membership_id=:nextOwnerId, next_follow_up_at=:nextFollowUpAt,
                  failure_reason_id=:failureReasonId, failure_reason_other=:failureReasonOther,
                  result_interaction_id=:interactionId, completed_at=now(), updated_at=now(), version=version+1,
                  actual_ended_at=coalesce(actual_ended_at, :completedAt),
                  feedback_due_at=coalesce(feedback_due_at, :feedbackDueAt)
                where id=:id and organization_id=:organizationId and version=:version
                """, params("resultSummary", clean(body.resultSummary()), "continueFollowUp", body.continueFollowUp(),
                "nextStep", clean(body.nextStep()), "nextOwnerId", body.nextOwnerMembershipId(),
                "nextFollowUpAt", timestamp(body.nextFollowUpAt()), "failureReasonId", body.failureReasonId(),
                "failureReasonOther", TextNormalizer.optional(body.failureReasonOther()),
                "completedAt", timestamp(completedAt),
                "feedbackDueAt", timestamp(completedAt.plus(Duration.ofHours(dueHours))),
                "interactionId", interaction.id(), "id", id, "organizationId", actor.organizationId(),
                "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        jdbc.update("""
                insert into customer_access_grants
                  (id, organization_id, customer_id, grantee_membership_id, can_edit, granted_by_membership_id)
                select :grantId, :organizationId, customer.id, :nextOwnerId, true, :grantedBy
                from customers customer
                where customer.id=:customerId and customer.organization_id=:organizationId
                  and customer.owner_membership_id<>:nextOwnerId
                on conflict (organization_id, customer_id, grantee_membership_id)
                  where grantee_membership_id is not null do update set can_edit=true
                """, params("grantId", UUID.randomUUID(), "organizationId", actor.organizationId(),
                "customerId", current.customerId(), "nextOwnerId", body.nextOwnerMembershipId(),
                "grantedBy", actor.membershipId()));
        jdbc.update("""
                update reminders set assigned_membership_id=:nextOwnerId, updated_at=now(), version=version+1
                where organization_id=:organizationId and customer_id=:customerId
                  and interaction_id=:interactionId and reminder_type='MANUAL' and status='PENDING'
                """, params("nextOwnerId", body.nextOwnerMembershipId(), "organizationId", actor.organizationId(),
                "customerId", current.customerId(), "interactionId", interaction.id()));
        jdbc.update("""
                update reminders set status='COMPLETED', completed_at=now(), updated_at=now()
                where organization_id=:organizationId and customer_id=:customerId and related_resource_id=:consultationId
                  and reminder_type='CONSULTATION_FEEDBACK' and status='PENDING'
                """, params("organizationId", actor.organizationId(), "customerId", current.customerId(),
                "consultationId", id));
        followUps.reconcileCustomer(actor.organizationId(), current.customerId());
        audit.record(actor, "CONSULTATION_RESULT_COMPLETED", "CONSULTATION", id.toString(), "SUCCESS",
                Map.of("interactionId", interaction.id().toString()));
        return require(id, actor.organizationId());
    }

    @Transactional(readOnly = true)
    public ConsultationDtos.Settings settings() {
        CurrentActor actor = actors.require();
        return settings(actor.organizationId());
    }

    @Transactional(readOnly = true)
    public ConsultationDtos.Settings adminSettings() {
        CurrentActor actor = requireAdmin();
        return settings(actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<ConsultationDtos.ConsultantCandidate> candidates() {
        CurrentActor actor = actors.require();
        return jdbc.query("""
                select membership.id, account.display_name,
                  array_agg(distinct role.code order by role.code) roles,
                  coalesce(array_agg(distinct team.name order by team.name)
                    filter (where team.id is not null), array[]::varchar[]) teams
                from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                join membership_roles membership_role on membership_role.membership_id=membership.id
                join roles role on role.id=membership_role.role_id
                left join team_members team_member on team_member.membership_id=membership.id
                left join teams team on team.id=team_member.team_id
                where membership.organization_id=:organizationId and membership.status='ACTIVE'
                  and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER')
                group by membership.id, account.display_name
                order by account.display_name
                """, params("organizationId", actor.organizationId()), (row, index) ->
                new ConsultationDtos.ConsultantCandidate(row.getObject("id", UUID.class),
                        row.getString("display_name"), List.of((String[]) row.getArray("roles").getArray()),
                        List.of((String[]) row.getArray("teams").getArray())));
    }

    @Transactional
    public ConsultationDtos.Settings updateSettings(ConsultationDtos.UpdateSettings body) {
        CurrentActor actor = requireAdmin();
        validateSettings(body);
        int changed = jdbc.update("""
                update consultation_settings set business_start=:businessStart, business_end=:businessEnd,
                  minimum_lead_minutes=:lead, allowed_durations=cast(:durations as jsonb),
                  daily_capacity=:capacity, feedback_due_hours=:feedback,
                  updated_by_membership_id=:membershipId, updated_at=now(), version=version+1
                where organization_id=:organizationId and version=:version
                """, params("businessStart", Time.valueOf(body.businessStart()),
                "businessEnd", Time.valueOf(body.businessEnd()), "lead", body.minimumLeadMinutes(),
                "durations", json(new LinkedHashSet<>(body.allowedDurations())), "capacity", body.dailyCapacity(),
                "feedback", body.feedbackDueHours(), "membershipId", actor.membershipId(),
                "organizationId", actor.organizationId(), "version", body.expectedVersion()));
        if (changed == 0) throw stale();
        audit.record(actor, "CONSULTATION_SETTINGS_UPDATED", "ORGANIZATION", actor.organizationId().toString(),
                "SUCCESS", null);
        return settings(actor.organizationId());
    }

    @Transactional(readOnly = true)
    public List<ConsultationDtos.FailureReason> failureReasons(boolean includeInactive) {
        CurrentActor actor = actors.require();
        String inactive = includeInactive && actor.isAdmin() ? "" : " and active=true";
        return jdbc.query("""
                select id, code, label, active, sort_order from consultation_failure_reasons
                where organization_id=:organizationId
                """ + inactive + " order by sort_order, label",
                params("organizationId", actor.organizationId()), (row, index) -> new ConsultationDtos.FailureReason(
                        row.getObject("id", UUID.class), row.getString("code"), row.getString("label"),
                        row.getBoolean("active"), row.getInt("sort_order")));
    }

    @Transactional
    public ConsultationDtos.FailureReason saveFailureReason(UUID id, ConsultationDtos.SaveFailureReason body) {
        CurrentActor actor = requireAdmin();
        UUID reasonId = id == null ? UUID.randomUUID() : id;
        MapSqlParameterSource values = params("id", reasonId, "organizationId", actor.organizationId(),
                "code", clean(body.code()).toUpperCase(), "label", clean(body.label()),
                "active", body.active(), "sortOrder", body.sortOrder());
        if (id == null) {
            jdbc.update("""
                    insert into consultation_failure_reasons
                      (id, organization_id, code, label, active, sort_order)
                    values (:id, :organizationId, :code, :label, :active, :sortOrder)
                    """, values);
        } else {
            int changed = jdbc.update("""
                    update consultation_failure_reasons set label=:label, active=:active,
                      sort_order=:sortOrder, updated_at=now()
                    where id=:id and organization_id=:organizationId
                    """, values);
            if (changed == 0) {
                throw ApiException.notFound("CONSULTATION_REASON_NOT_FOUND", "未推进原因不存在");
            }
        }
        audit.record(actor, "CONSULTATION_FAILURE_REASON_SAVED", "CONSULTATION_REASON", reasonId.toString(),
                "SUCCESS", null);
        return jdbc.queryForObject("""
                select id, code, label, active, sort_order from consultation_failure_reasons
                where organization_id=:organizationId and id=:id
                """, params("organizationId", actor.organizationId(), "id", reasonId),
                (row, index) -> new ConsultationDtos.FailureReason(row.getObject("id", UUID.class),
                        row.getString("code"), row.getString("label"), row.getBoolean("active"),
                        row.getInt("sort_order")));
    }

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Shanghai")
    @Transactional
    public void advanceAndRemind() {
        jdbc.update("""
                update consultation_handoffs handoff set status='AWAITING_FEEDBACK',
                  actual_ended_at=coalesce(actual_ended_at, scheduled_end),
                  feedback_due_at=coalesce(feedback_due_at, scheduled_end + make_interval(hours => settings.feedback_due_hours)),
                  updated_at=now(), version=handoff.version+1
                from consultation_settings settings
                where settings.organization_id=handoff.organization_id and handoff.status='SCHEDULED'
                  and handoff.scheduled_end<=now()
                """, Map.of());
        List<Map<String, Object>> overdue = jdbc.queryForList("""
                select handoff.id consultation_id, handoff.organization_id, handoff.customer_id, customer.full_name,
                       handoff.consultant_membership_id, customer.owner_membership_id, handoff.feedback_due_at
                from consultation_handoffs handoff join customers customer on customer.id=handoff.customer_id
                where handoff.status='AWAITING_FEEDBACK' and handoff.feedback_due_at<=now()
                """, Map.of());
        LocalDate day = LocalDate.now(BUSINESS_ZONE);
        for (Map<String, Object> row : overdue) {
            Set<UUID> recipients = new LinkedHashSet<>();
            recipients.add((UUID) row.get("consultant_membership_id"));
            Instant feedbackDueAt = ((java.sql.Timestamp) row.get("feedback_due_at")).toInstant();
            Duration overdueFor = Duration.between(feedbackDueAt, Instant.now());
            if (overdueFor.toHours() >= 24) recipients.add((UUID) row.get("owner_membership_id"));
            if (overdueFor.toHours() >= 48) {
                recipients.addAll(jdbc.queryForList("""
                        select membership.id from memberships membership
                        join membership_roles membership_role on membership_role.membership_id=membership.id
                        join roles role on role.id=membership_role.role_id and role.code='ORG_ADMIN'
                        join user_accounts account on account.id=membership.user_id and account.enabled=true
                        where membership.organization_id=:organizationId and membership.status='ACTIVE'
                        """, params("organizationId", row.get("organization_id")), UUID.class));
            }
            for (UUID recipient : recipients) {
                jdbc.update("""
                        insert into reminders
                          (id, organization_id, customer_id, assigned_membership_id, remind_at, title,
                           status, delivery_status, reminder_type, automatic_day, related_resource_id)
                        values (:id, :organizationId, :customerId, :recipient, now(), :title,
                          'PENDING','UNSENT','CONSULTATION_FEEDBACK',:day,:consultationId)
                        on conflict (organization_id, assigned_membership_id, reminder_type, related_resource_id)
                          where reminder_type<>'MANUAL' and status='PENDING' and related_resource_id is not null
                        do update set remind_at=excluded.remind_at, title=excluded.title,
                          automatic_day=excluded.automatic_day, delivery_status='UNSENT', updated_at=now()
                        where reminders.automatic_day is distinct from excluded.automatic_day
                        """, params("id", UUID.randomUUID(), "organizationId", row.get("organization_id"),
                        "customerId", row.get("customer_id"), "recipient", recipient,
                        "consultationId", row.get("consultation_id"),
                        "title", "咨询结果已超过反馈时限：" + row.get("full_name"), "day", day));
            }
        }
    }

    private ConsultationDtos.Response require(UUID id, UUID organizationId) {
        List<ConsultationDtos.Response> rows = jdbc.query(baseSelect()
                        + " where handoff.organization_id=:organizationId and handoff.id=:id",
                params("organizationId", organizationId, "id", id), this::map);
        if (rows.isEmpty()) throw ApiException.notFound("CONSULTATION_NOT_FOUND", "咨询交接不存在");
        return rows.getFirst();
    }

    private String baseSelect() {
        return """
                select handoff.*, customer.full_name customer_name,
                       creator_account.display_name creator_name,
                       consultant_account.display_name consultant_name
                from consultation_handoffs handoff
                join customers customer on customer.id=handoff.customer_id
                join memberships creator on creator.id=handoff.created_by_membership_id
                join user_accounts creator_account on creator_account.id=creator.user_id
                join memberships consultant on consultant.id=handoff.consultant_membership_id
                join user_accounts consultant_account on consultant_account.id=consultant.user_id
                """;
    }

    private ConsultationDtos.Response map(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        List<UUID> sourceIds;
        Map<String, Object> customerSnapshot;
        try {
            sourceIds = objectMapper.readValue(row.getString("source_interaction_ids"), new TypeReference<>() {});
            customerSnapshot = objectMapper.readValue(row.getString("customer_snapshot"), new TypeReference<>() {});
        } catch (Exception exception) {
            sourceIds = List.of();
            customerSnapshot = Map.of();
        }
        return new ConsultationDtos.Response(
                row.getObject("id", UUID.class), row.getObject("customer_id", UUID.class), row.getString("customer_name"),
                customerSnapshot,
                row.getObject("created_by_membership_id", UUID.class), row.getString("creator_name"),
                row.getObject("consultant_membership_id", UUID.class), row.getString("consultant_name"), sourceIds,
                row.getString("consultation_reason"), row.getString("customer_quotes"), row.getString("goals_motivation"),
                row.getString("actions_taken"), row.getString("main_concerns"), row.getString("decision_participants"),
                row.getBoolean("decision_participant_consent"), row.getString("product_fit"),
                row.getString("suggested_focus"), row.getString("explicit_taboos"), row.getString("expected_result"),
                instant(row, "scheduled_start"), instant(row, "scheduled_end"), row.getString("status"),
                row.getString("rejection_reason"), instant(row, "accepted_at"), instant(row, "actual_ended_at"),
                instant(row, "feedback_due_at"), row.getString("result_summary"),
                row.getObject("continue_follow_up", Boolean.class), row.getString("next_step"),
                row.getObject("next_owner_membership_id", UUID.class), instant(row, "next_follow_up_at"),
                row.getObject("failure_reason_id", UUID.class), row.getString("failure_reason_other"),
                row.getObject("result_interaction_id", UUID.class), instant(row, "created_at"),
                instant(row, "updated_at"), row.getLong("version"));
    }

    private void validateConsent(String participants, boolean consent) {
        if (TextNormalizer.optional(participants) != null && !consent) {
            throw ApiException.badRequest("DECISION_PARTICIPANT_CONSENT_REQUIRED", "记录决策参与人前必须确认客户自愿提供且业务必要");
        }
    }

    private void validateConsultant(UUID organizationId, UUID membershipId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                where membership.id=:membershipId and membership.organization_id=:organizationId
                  and membership.status='ACTIVE' and exists(
                    select 1 from membership_roles membership_role join roles role on role.id=membership_role.role_id
                    where membership_role.membership_id=membership.id
                      and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER'))
                """, params("membershipId", membershipId, "organizationId", organizationId), Integer.class);
        if (count == null || count == 0) {
            throw ApiException.badRequest("CONSULTANT_NOT_ELIGIBLE", "咨询顾问必须是当前组织内启用的可写成员");
        }
    }

    private void validateSources(UUID organizationId, UUID customerId, List<UUID> sourceIds) {
        Set<UUID> unique = sourceIds == null ? Set.of() : new LinkedHashSet<>(sourceIds);
        if (unique.isEmpty()) throw ApiException.badRequest("CONSULTATION_SOURCE_REQUIRED", "咨询交接必须引用至少一条客户时间线");
        if (unique.size() > 50) throw ApiException.badRequest("TOO_MANY_SOURCE_INTERACTIONS", "最多引用 50 条时间线记录");
        if (unique.isEmpty()) return;
        Integer count = jdbc.queryForObject("""
                select count(*) from interactions where organization_id=:organizationId
                  and customer_id=:customerId and id in (:sourceIds)
                """, params("organizationId", organizationId, "customerId", customerId, "sourceIds", unique), Integer.class);
        if (count == null || count != unique.size()) {
            throw ApiException.badRequest("INVALID_SOURCE_INTERACTION", "引用的时间线记录不属于当前客户");
        }
    }

    private void validateSchedule(UUID organizationId, UUID consultantId, Instant start, Instant end, UUID excludeId) {
        if (end == null || start == null || !end.isAfter(start)) {
            throw ApiException.badRequest("INVALID_CONSULTATION_SCHEDULE", "咨询结束时间必须晚于开始时间");
        }
        ConsultationDtos.Settings settings = settings(organizationId);
        if (start.isBefore(Instant.now().plus(Duration.ofMinutes(settings.minimumLeadMinutes())))) {
            throw ApiException.badRequest("CONSULTATION_LEAD_TIME_REQUIRED", "预约时间未达到最小提前量");
        }
        var localStart = start.atZone(BUSINESS_ZONE);
        var localEnd = end.atZone(BUSINESS_ZONE);
        if (!localStart.toLocalDate().equals(localEnd.toLocalDate())
                || localStart.toLocalTime().isBefore(settings.businessStart())
                || localEnd.toLocalTime().isAfter(settings.businessEnd())) {
            throw ApiException.badRequest("CONSULTATION_OUTSIDE_BUSINESS_HOURS", "预约必须位于组织设置的营业时段内");
        }
        int duration = (int) Duration.between(start, end).toMinutes();
        if (!settings.allowedDurations().contains(duration)) {
            throw ApiException.badRequest("CONSULTATION_DURATION_NOT_ALLOWED", "请选择管理员设置的咨询时长");
        }
        // Serialize overlap and daily-capacity checks for the same consultant/day.
        // This closes the race where two concurrent requests both observe an empty slot.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(:lockKey, 0)) is null",
                params("lockKey", organizationId + ":" + consultantId + ":" + localStart.toLocalDate()),
                Boolean.class);
        MapSqlParameterSource query = params("organizationId", organizationId, "consultantId", consultantId,
                "start", timestamp(start), "end", timestamp(end),
                "dateStart", timestamp(localStart.toLocalDate().atStartOfDay(BUSINESS_ZONE).toInstant()),
                "dateEnd", timestamp(localStart.toLocalDate().plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant()),
                "excludeId", excludeId == null ? UUID.fromString("00000000-0000-0000-0000-000000000000") : excludeId);
        Boolean conflict = jdbc.queryForObject("""
                select exists(select 1 from consultation_handoffs
                  where organization_id=:organizationId and consultant_membership_id=:consultantId
                    and id<>:excludeId and status in ('PENDING_ACCEPTANCE','SCHEDULED','AWAITING_FEEDBACK')
                    and scheduled_start<:end and scheduled_end>:start)
                """, query, Boolean.class);
        if (Boolean.TRUE.equals(conflict)) throw ApiException.conflict("CONSULTATION_TIME_CONFLICT", "该顾问在此时段已有咨询");
        Integer count = jdbc.queryForObject("""
                select count(*) from consultation_handoffs
                where organization_id=:organizationId and consultant_membership_id=:consultantId
                  and id<>:excludeId and status in ('PENDING_ACCEPTANCE','SCHEDULED','AWAITING_FEEDBACK','COMPLETED')
                  and scheduled_start>=:dateStart and scheduled_start<:dateEnd
                """, query, Integer.class);
        if (count != null && count >= settings.dailyCapacity()) {
            throw ApiException.conflict("CONSULTATION_DAILY_CAPACITY_REACHED", "该顾问当天咨询容量已满");
        }
    }

    private void validateResult(UUID organizationId, ConsultationDtos.CompleteRequest body) {
        validateConsultant(organizationId, body.nextOwnerMembershipId());
        if (!body.continueFollowUp()) {
            if (body.failureReasonId() == null) {
                throw ApiException.badRequest("CONSULTATION_FAILURE_REASON_REQUIRED", "未继续推进时必须选择失败原因");
            }
            String code = jdbc.query("""
                    select code from consultation_failure_reasons where id=:id
                      and organization_id=:organizationId and active=true
                    """, params("id", body.failureReasonId(), "organizationId", organizationId),
                    result -> result.next() ? result.getString(1) : null);
            if (code == null) throw ApiException.badRequest("CONSULTATION_FAILURE_REASON_INVALID", "失败原因不存在或已停用");
            if ("OTHER".equals(code) && TextNormalizer.optional(body.failureReasonOther()) == null) {
                throw ApiException.badRequest("CONSULTATION_FAILURE_OTHER_REQUIRED", "选择其他时必须填写说明");
            }
        }
    }

    private void validateSettings(ConsultationDtos.UpdateSettings body) {
        if (!body.businessStart().isBefore(body.businessEnd())) {
            throw ApiException.badRequest("INVALID_BUSINESS_HOURS", "营业开始时间必须早于结束时间");
        }
        if (body.allowedDurations().stream().distinct().count() != body.allowedDurations().size()) {
            throw ApiException.badRequest("DUPLICATE_CONSULTATION_DURATION", "咨询时长不能重复");
        }
    }

    private ConsultationDtos.Settings settings(UUID organizationId) {
        return jdbc.queryForObject("""
                select business_start, business_end, minimum_lead_minutes, allowed_durations::text,
                  daily_capacity, feedback_due_hours, version
                from consultation_settings where organization_id=:organizationId
                """, params("organizationId", organizationId), (row, index) -> {
            try {
                List<Integer> durations = objectMapper.readValue(row.getString("allowed_durations"), new TypeReference<>() {});
                return new ConsultationDtos.Settings(row.getTime("business_start").toLocalTime(),
                        row.getTime("business_end").toLocalTime(), row.getInt("minimum_lead_minutes"),
                        durations, row.getInt("daily_capacity"), row.getInt("feedback_due_hours"), row.getLong("version"));
            } catch (Exception exception) {
                throw new IllegalStateException("Invalid consultation duration configuration", exception);
            }
        });
    }

    private CurrentActor requireAdmin() {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin()) throw ApiException.forbidden("只有组织管理员可以维护咨询设置");
        return actor;
    }

    private void requireConsultantOrAdmin(ConsultationDtos.Response current, CurrentActor actor) {
        if (!current.consultantMembershipId().equals(actor.membershipId()) && !actor.isAdmin()) {
            throw ApiException.forbidden("只有指定顾问或管理员可以回填咨询结果");
        }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }
    private ApiException stale() { return ApiException.conflict("STALE_VERSION", "交接记录已变化，请刷新后重试"); }
    private Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
    private MapSqlParameterSource params(Object... values) {
        MapSqlParameterSource result = new MapSqlParameterSource();
        for (int index = 0; index < values.length; index += 2) result.addValue((String) values[index], values[index + 1]);
        return result;
    }
}
