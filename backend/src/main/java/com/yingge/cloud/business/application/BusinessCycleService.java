package com.yingge.cloud.business.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.business.api.BusinessCycleDtos;
import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.business.domain.BusinessParticipantRole;
import com.yingge.cloud.business.domain.DealForecastOutcome;
import com.yingge.cloud.business.infrastructure.BusinessCycleEntity;
import com.yingge.cloud.business.infrastructure.BusinessCycleEventEntity;
import com.yingge.cloud.business.infrastructure.BusinessCycleEventRepository;
import com.yingge.cloud.business.infrastructure.BusinessCycleParticipantSnapshotEntity;
import com.yingge.cloud.business.infrastructure.BusinessCycleParticipantSnapshotRepository;
import com.yingge.cloud.business.infrastructure.BusinessCycleRepository;
import com.yingge.cloud.business.infrastructure.BusinessCycleSpecifications;
import com.yingge.cloud.business.infrastructure.CustomerReferralEntity;
import com.yingge.cloud.business.infrastructure.CustomerReferralRepository;
import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.domain.CustomerQuality;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.api.PageResponse;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BusinessCycleService {
    private static final List<BusinessCycleStatus> ACTIVE_STATUSES =
            List.of(BusinessCycleStatus.IN_PROGRESS, BusinessCycleStatus.LOCK_PENDING,
                    BusinessCycleStatus.FORMALLY_QUOTED, BusinessCycleStatus.WON_PENDING);

    private final BusinessCycleRepository cycles;
    private final BusinessCycleParticipantSnapshotRepository snapshots;
    private final BusinessCycleEventRepository events;
    private final CustomerReferralRepository referrals;
    private final CustomerService customers;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final BusinessMembershipDirectory membershipDirectory;
    private final AuditService audit;
    private final NotificationService notifications;
    private final DealForecastService dealForecasts;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public BusinessCycleService(BusinessCycleRepository cycles,
                                BusinessCycleParticipantSnapshotRepository snapshots,
                                BusinessCycleEventRepository events,
                                CustomerReferralRepository referrals,
                                CustomerService customers,
                                CurrentActorProvider actors,
                                PermissionService permissions,
                                BusinessMembershipDirectory membershipDirectory,
                                AuditService audit,
                                NotificationService notifications,
                                DealForecastService dealForecasts,
                                JdbcTemplate jdbc,
                                ObjectMapper objectMapper) {
        this.cycles = cycles;
        this.snapshots = snapshots;
        this.events = events;
        this.referrals = referrals;
        this.customers = customers;
        this.actors = actors;
        this.permissions = permissions;
        this.membershipDirectory = membershipDirectory;
        this.audit = audit;
        this.notifications = notifications;
        this.dealForecasts = dealForecasts;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<BusinessCycleDtos.Response> list(UUID customerId) {
        CurrentActor actor = actors.require();
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        return cycles.findByOrganizationIdAndCustomerIdOrderByCycleNumberDesc(actor.organizationId(), customerId)
                .stream().map(cycle -> response(cycle, customer.getFullName(), false)).toList();
    }

    @Transactional(readOnly = true)
    public BusinessCycleDtos.Response get(UUID customerId, UUID cycleId) {
        CurrentActor actor = actors.require();
        VisibleCycle visible = requireVisibleCycle(customerId, cycleId, actor);
        return response(visible.cycle(), visible.customer().getFullName(), true);
    }

    @Transactional(readOnly = true)
    public List<BusinessCycleDtos.ParticipantChangeResponse> participantChanges(
            UUID customerId, UUID cycleId) {
        CurrentActor actor = actors.require();
        requireVisibleCycle(customerId, cycleId, actor);
        return participantChanges(actor.organizationId(), cycleId, false);
    }

    @Transactional(readOnly = true)
    public List<BusinessCycleDtos.ParticipantChangeResponse> adminParticipantChanges() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        return participantChanges(actor.organizationId(), null, true);
    }

    @Transactional
    public BusinessCycleDtos.Response create(UUID customerId, BusinessCycleDtos.CreateRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        CustomerEntity customer = requireWritableCustomer(customerId, actor);
        String idempotencyHash = hash(body.idempotencyKey());
        var existing = cycles.findByOrganizationIdAndCreatedByMembershipIdAndIdempotencyKeyHash(
                actor.organizationId(), actor.membershipId(), idempotencyHash);
        if (existing.isPresent()) {
            if (!existing.get().getCustomerId().equals(customerId)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已用于其他客户业务周期");
            }
            return response(existing.get(), customer.getFullName(), true);
        }

        membershipDirectory.lockCustomer(actor.organizationId(), customerId);
        if (cycles.existsByOrganizationIdAndCustomerIdAndStatusIn(actor.organizationId(), customerId, ACTIVE_STATUSES)) {
            throw ApiException.conflict("ACTIVE_BUSINESS_CYCLE_EXISTS", "当前客户已有进行中的业务周期");
        }
        int cycleNumber = cycles.maxCycleNumber(actor.organizationId(), customerId) + 1;
        BusinessCycleEntity cycle = cycles.saveAndFlush(new BusinessCycleEntity(
                actor.organizationId(), customerId, cycleNumber, TextNormalizer.required(body.title()),
                TextNormalizer.optional(body.summary()), idempotencyHash, actor.membershipId()));
        recordEvent(actor, cycle, "CYCLE_CREATED", null, cycle.getStatus(),
                Map.of("cycleNumber", cycleNumber, "cycleType", cycle.getCycleType().name()));
        audit.record(actor, "CUSTOMER_BUSINESS_CYCLE_CREATED", "CUSTOMER_BUSINESS_CYCLE",
                cycle.getId().toString(), "SUCCESS", Map.of("customerId", customerId.toString(),
                        "cycleNumber", cycleNumber));
        return response(cycle, customer.getFullName(), true);
    }

    @Transactional
    public BusinessCycleDtos.Response requestParticipantLock(
            UUID customerId, UUID cycleId, BusinessCycleDtos.LockParticipantsRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        VisibleCycle visible = requireWritableCycle(customerId, cycleId, actor);
        BusinessCycleEntity cycle = visible.cycle();
        requireVersion(cycle, body.expectedVersion());
        requireStatus(cycle, BusinessCycleStatus.IN_PROGRESS, "只有进行中的业务周期可以申请主辅锁定");
        ParticipantSelection selection = validateParticipants(actor, body.primaryMembershipId(),
                body.assistantMembershipIds());
        BusinessCycleStatus previous = cycle.getStatus();
        cycle.requestParticipantLock(TextNormalizer.required(body.reason()), selection.primaryId(),
                selection.assistantIds(), actor.membershipId());
        cycles.saveAndFlush(cycle);
        recordEvent(actor, cycle, "PARTICIPANT_LOCK_REQUESTED", previous, cycle.getStatus(), Map.of(
                "primaryMembershipId", selection.primaryId().toString(),
                "assistantMembershipIds", selection.assistantIds().stream().map(UUID::toString).toList(),
                "reason", TextNormalizer.required(body.reason())));
        audit.record(actor, "CUSTOMER_BUSINESS_PARTICIPANT_LOCK_REQUESTED", "CUSTOMER_BUSINESS_CYCLE",
                cycle.getId().toString(), "SUCCESS", Map.of("customerId", customerId.toString()));
        publish(actor, cycle, selection, "BUSINESS_QUOTE_LOCK_PENDING", "报价主辅锁定待确认",
                "客户业务周期 #" + cycle.getCycleNumber() + " 已提交报价前主辅锁定申请。",
                "quote-lock-pending-" + cycle.getId() + "-" + cycle.getVersion());
        return response(cycle, visible.customer().getFullName(), true);
    }

    @Transactional
    public BusinessCycleDtos.Response confirmQuote(UUID cycleId, BusinessCycleDtos.ConfirmQuoteRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        BusinessCycleEntity cycle = requireOrganizationCycle(cycleId, actor);
        CustomerEntity customer = customers.requireVisibleEntity(cycle.getCustomerId(), actor);
        requireVersion(cycle, body.expectedVersion());
        requireStatus(cycle, BusinessCycleStatus.LOCK_PENDING, "只有待锁定业务周期可以确认正式报价");
        if (body.quotedAt().isAfter(Instant.now())) {
            throw ApiException.badRequest("QUOTE_TIME_IN_FUTURE", "正式报价时间不能晚于当前时间");
        }
        ParticipantSelection selection = validateParticipants(actor, body.primaryMembershipId(),
                body.assistantMembershipIds());
        if (!selection.primaryId().equals(cycle.getSuggestedPrimaryMembershipId())
                || !new LinkedHashSet<>(selection.assistantIds()).equals(
                new LinkedHashSet<>(cycle.getSuggestedAssistantMembershipIds()))) {
            throw ApiException.conflict("QUOTE_PARTICIPANTS_DIFFER_FROM_REQUEST",
                    "管理员确认的主辅名单必须与申请一致；如需调整请退回后重新申请");
        }
        String exceptionReason = TextNormalizer.optional(body.emergencyExceptionReason());
        if (isAClass(customer) && !membershipDirectory.hasActiveSecondLine(actor.organizationId(), customer.getId())
                && exceptionReason == null) {
            throw ApiException.conflict("A_SECOND_LINE_REQUIRED", "A 类客户缺少有效二线，正式报价前需补齐或填写紧急例外理由");
        }
        BusinessCycleStatus previous = cycle.getStatus();
        boolean crossSale = isCrossSale(actor.organizationId(), cycle.getCustomerId());
        int snapshotVersion = cycle.confirmFormalQuote(TextNormalizer.required(body.quoteType()), body.quotedAt(),
                crossSale, exceptionReason, actor.membershipId());
        cycles.saveAndFlush(cycle);
        saveParticipantSnapshot(actor, cycle, snapshotVersion, selection,
                exceptionReason == null ? "正式报价主辅锁定" : "紧急例外正式报价锁定");
        recordEvent(actor, cycle, "FORMAL_QUOTE_CONFIRMED", previous, cycle.getStatus(), Map.of(
                "quoteType", TextNormalizer.required(body.quoteType()),
                "quotedAt", body.quotedAt().toString(), "snapshotVersion", snapshotVersion));
        audit.record(actor, "CUSTOMER_BUSINESS_FORMAL_QUOTE_CONFIRMED", "CUSTOMER_BUSINESS_CYCLE",
                cycle.getId().toString(), "SUCCESS", Map.of("customerId", customer.getId().toString(),
                        "quoteType", TextNormalizer.required(body.quoteType()),
                        "snapshotVersion", snapshotVersion));
        publish(actor, cycle, selection, "BUSINESS_FORMALLY_QUOTED", "正式报价与主辅名单已锁定",
                "客户业务周期 #" + cycle.getCycleNumber() + " 已记录正式报价，主辅名单已锁定。",
                "formally-quoted-" + cycle.getId() + "-" + snapshotVersion);
        return response(cycle, customer.getFullName(), true);
    }

    @Transactional
    public BusinessCycleDtos.Response rejectQuoteLock(
            UUID cycleId, BusinessCycleDtos.RejectQuoteLockRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        BusinessCycleEntity cycle = requireOrganizationCycle(cycleId, actor);
        CustomerEntity customer = customers.requireVisibleEntity(cycle.getCustomerId(), actor);
        requireVersion(cycle, body.expectedVersion());
        requireStatus(cycle, BusinessCycleStatus.LOCK_PENDING, "只有待锁定业务周期可以退回");
        String reason = TextNormalizer.required(body.reason());
        LinkedHashSet<UUID> recipients = new LinkedHashSet<>();
        recipients.add(actor.membershipId());
        if (cycle.getLockRequestedByMembershipId() != null) recipients.add(cycle.getLockRequestedByMembershipId());
        if (cycle.getSuggestedPrimaryMembershipId() != null) recipients.add(cycle.getSuggestedPrimaryMembershipId());
        recipients.addAll(cycle.getSuggestedAssistantMembershipIds());
        BusinessCycleStatus previous = cycle.getStatus();
        cycle.rejectParticipantLock();
        cycles.saveAndFlush(cycle);
        recordEvent(actor, cycle, "PARTICIPANT_LOCK_REJECTED", previous, cycle.getStatus(), Map.of("reason", reason));
        audit.record(actor, "CUSTOMER_BUSINESS_PARTICIPANT_LOCK_REJECTED", "CUSTOMER_BUSINESS_CYCLE",
                cycle.getId().toString(), "SUCCESS", Map.of("customerId", customer.getId().toString(), "reason", reason));
        notifications.publish(actor.organizationId(), membershipDirectory.notificationRecipients(
                        actor.organizationId(), cycle.getCustomerId(), recipients),
                "BUSINESS_QUOTE_LOCK_REJECTED", "报价主辅锁定已退回",
                "客户业务周期 #" + cycle.getCycleNumber() + " 的锁定申请已退回：" + reason,
                "/customers/" + cycle.getCustomerId(), "quote-lock-rejected-" + cycle.getId() + "-" + cycle.getVersion());
        return response(cycle, customer.getFullName(), true);
    }

    @Transactional
    public BusinessCycleDtos.Response declareWon(UUID customerId, UUID cycleId,
                                                  BusinessCycleDtos.DeclareWonRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        VisibleCycle visible = requireWritableCycle(customerId, cycleId, actor);
        BusinessCycleEntity cycle = visible.cycle();
        requireVersion(cycle, body.expectedVersion());
        requireStatus(cycle, BusinessCycleStatus.FORMALLY_QUOTED, "只有已正式报价并锁定主辅名单的业务周期可以申报成单");
        ParticipantSelection selection = validateParticipants(actor, body.suggestedPrimaryMembershipId(),
                body.suggestedAssistantMembershipIds());
        if (!selection.primaryId().equals(cycle.getSuggestedPrimaryMembershipId())
                || !new LinkedHashSet<>(selection.assistantIds()).equals(
                new LinkedHashSet<>(cycle.getSuggestedAssistantMembershipIds()))) {
            throw ApiException.conflict("LOCKED_PARTICIPANTS_CHANGED", "正式报价后不能在成单申报中变更主辅名单");
        }
        BusinessCycleStatus previous = cycle.getStatus();
        cycle.declareWon(TextNormalizer.required(body.fact()), actor.membershipId());
        cycles.saveAndFlush(cycle);
        recordEvent(actor, cycle, "WON_DECLARED", previous, cycle.getStatus(), Map.of(
                "suggestedPrimaryMembershipId", selection.primaryId().toString(),
                "suggestedAssistantMembershipIds", selection.assistantIds().stream().map(UUID::toString).toList()
        ));
        audit.record(actor, "CUSTOMER_BUSINESS_WIN_DECLARED", "CUSTOMER_BUSINESS_CYCLE",
                cycle.getId().toString(), "SUCCESS", Map.of("customerId", customerId.toString()));
        publish(actor, cycle, selection, "BUSINESS_WON_PENDING", "有新的成单申报待确认",
                "客户业务周期 #" + cycle.getCycleNumber() + " 已提交成单事实，请在系统内核对。",
                "won-pending-" + cycle.getId() + "-" + cycle.getVersion());
        return response(cycle, visible.customer().getFullName(), true);
    }

    @Transactional
    public BusinessCycleDtos.Response markLost(UUID customerId, UUID cycleId,
                                               BusinessCycleDtos.MarkLostRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        VisibleCycle visible = requireWritableCycle(customerId, cycleId, actor);
        BusinessCycleEntity cycle = visible.cycle();
        requireVersion(cycle, body.expectedVersion());
        requireOpenForMemberResult(cycle);
        String competitorName = TextNormalizer.optional(body.competitorName());
        String fact = TextNormalizer.optional(body.fact());
        if (competitorName == null && fact == null) {
            throw ApiException.badRequest("COMPETITOR_FACT_REQUIRED", "请填写友商名称或可核查的事实说明");
        }
        BusinessCycleStatus previous = cycle.getStatus();
        cycle.markLost(competitorName, fact);
        cycles.saveAndFlush(cycle);
        Map<String, Object> details = new LinkedHashMap<>();
        if (competitorName != null) details.put("competitorName", competitorName);
        if (fact != null) details.put("fact", fact);
        recordEvent(actor, cycle, "LOST_TO_COMPETITOR_MARKED", previous, cycle.getStatus(), details);
        audit.record(actor, "CUSTOMER_BUSINESS_LOST_MARKED", "CUSTOMER_BUSINESS_CYCLE",
                cycle.getId().toString(), "SUCCESS", Map.of("customerId", customerId.toString()));
        publish(actor, cycle, null, "BUSINESS_LOST_TO_COMPETITOR", "业务结果已标记为友商流失",
                "客户业务周期 #" + cycle.getCycleNumber() + " 已记录可核查的友商流失事实。",
                "lost-" + cycle.getId() + "-" + cycle.getVersion());
        dealForecasts.closeForCycle(actor.organizationId(), cycle.getId(),
                DealForecastOutcome.LOST_TO_COMPETITOR, actor.membershipId());
        return response(cycle, visible.customer().getFullName(), true);
    }

    @Transactional
    public BusinessCycleDtos.Response cancel(UUID customerId, UUID cycleId,
                                             BusinessCycleDtos.CancelRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        VisibleCycle visible = requireWritableCycle(customerId, cycleId, actor);
        BusinessCycleEntity cycle = visible.cycle();
        requireVersion(cycle, body.expectedVersion());
        requireOpenForMemberResult(cycle);
        BusinessCycleStatus previous = cycle.getStatus();
        cycle.cancel();
        cycles.saveAndFlush(cycle);
        recordEvent(actor, cycle, "CYCLE_CANCELLED", previous, cycle.getStatus(),
                Map.of("reason", TextNormalizer.required(body.reason())));
        audit.record(actor, "CUSTOMER_BUSINESS_CYCLE_CANCELLED", "CUSTOMER_BUSINESS_CYCLE",
                cycle.getId().toString(), "SUCCESS", Map.of("customerId", customerId.toString()));
        dealForecasts.closeForCycle(actor.organizationId(), cycle.getId(),
                DealForecastOutcome.CANCELLED, actor.membershipId());
        return response(cycle, visible.customer().getFullName(), true);
    }

    @Transactional
    public BusinessCycleDtos.Response confirmWon(UUID cycleId, BusinessCycleDtos.ConfirmWonRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        BusinessCycleEntity cycle = requireOrganizationCycle(cycleId, actor);
        CustomerEntity customer = customers.requireVisibleEntity(cycle.getCustomerId(), actor);
        requireVersion(cycle, body.expectedVersion());
        requireStatus(cycle, BusinessCycleStatus.WON_PENDING, "只有待确认的成单申报可以完成确认");
        ParticipantSelection selection = validateParticipants(actor, body.primaryMembershipId(),
                body.assistantMembershipIds());
        if (!selection.primaryId().equals(cycle.getSuggestedPrimaryMembershipId())
                || !new LinkedHashSet<>(selection.assistantIds()).equals(
                new LinkedHashSet<>(cycle.getSuggestedAssistantMembershipIds()))) {
            throw ApiException.conflict("LOCKED_PARTICIPANTS_CHANGED", "成单确认必须沿用正式报价时锁定的主辅名单");
        }
        BusinessCycleStatus previous = cycle.getStatus();
        cycle.confirmWon(actor.membershipId());
        cycles.saveAndFlush(cycle);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("snapshotVersion", cycle.getCurrentSnapshotVersion());
        details.put("primaryMembershipId", selection.primaryId().toString());
        details.put("assistantMembershipIds", selection.assistantIds().stream().map(UUID::toString).toList());
        boolean crossSale = isCrossSale(actor.organizationId(), cycle.getCustomerId());
        details.put("crossSale", crossSale);
        recordEvent(actor, cycle, "WON_CONFIRMED", previous, cycle.getStatus(), details);
        audit.record(actor, "CUSTOMER_BUSINESS_WIN_CONFIRMED", "CUSTOMER_BUSINESS_CYCLE",
                cycle.getId().toString(), "SUCCESS", Map.of("customerId", customer.getId().toString(),
                        "snapshotVersion", cycle.getCurrentSnapshotVersion(), "crossSale", crossSale));
        publish(actor, cycle, selection, "BUSINESS_WON", "成单申报已确认",
                "客户业务周期 #" + cycle.getCycleNumber() + " 已由管理员确认，主辅快照已锁定。",
                "won-" + cycle.getId() + "-" + cycle.getCurrentSnapshotVersion());
        dealForecasts.closeForCycle(actor.organizationId(), cycle.getId(),
                DealForecastOutcome.WON, actor.membershipId());
        return response(cycle, customer.getFullName(), true);
    }

    @Transactional
    public BusinessCycleDtos.Response reviseParticipants(
            UUID cycleId, BusinessCycleDtos.ReviseParticipantsRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        throw ApiException.conflict("PARTICIPANT_CONFIRMATIONS_REQUIRED",
                "正式报价后的主辅调整必须创建变更申请，并由所有受影响成员确认后再由管理员完成");
    }

    @Transactional
    public BusinessCycleDtos.ParticipantChangeResponse requestParticipantChange(
            UUID cycleId, BusinessCycleDtos.ParticipantChangeRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        BusinessCycleEntity cycle = requireOrganizationCycle(cycleId, actor);
        requireVersion(cycle, body.expectedVersion());
        if (!Set.of(BusinessCycleStatus.FORMALLY_QUOTED, BusinessCycleStatus.WON).contains(cycle.getStatus())) {
            throw ApiException.conflict("INVALID_BUSINESS_CYCLE_STATUS", "只有正式报价后才能申请调整主辅名单");
        }
        ParticipantSelection selection = validateParticipants(actor, body.primaryMembershipId(),
                body.assistantMembershipIds());
        List<BusinessCycleParticipantSnapshotEntity> current = snapshots
                .findByBusinessCycleIdAndSnapshotVersionOrderByRoleTypeAscDisplayNameSnapshotAsc(
                        cycleId, cycle.getCurrentSnapshotVersion());
        Map<UUID, BusinessParticipantRole> currentRoles = new LinkedHashMap<>();
        current.forEach(item -> currentRoles.put(item.getMembershipId(), item.getRoleType()));
        Map<UUID, BusinessParticipantRole> proposedRoles = new LinkedHashMap<>();
        proposedRoles.put(selection.primaryId(), BusinessParticipantRole.PRIMARY);
        selection.assistantIds().forEach(id -> proposedRoles.put(id, BusinessParticipantRole.ASSISTANT));
        if (currentRoles.equals(proposedRoles)) {
            throw ApiException.badRequest("PARTICIPANT_CHANGE_EMPTY", "主辅名单没有变化");
        }
        LinkedHashSet<UUID> affected = new LinkedHashSet<>();
        LinkedHashSet<UUID> all = new LinkedHashSet<>(currentRoles.keySet());
        all.addAll(proposedRoles.keySet());
        all.forEach(id -> { if (currentRoles.get(id) != proposedRoles.get(id)) affected.add(id); });
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
                insert into customer_business_participant_change_requests
                  (id,organization_id,business_cycle_id,proposed_primary_membership_id,
                   proposed_assistant_membership_ids,affected_membership_ids,reason,
                   expected_cycle_version,created_by_membership_id)
                values (?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,?,?)
                """, requestId, actor.organizationId(), cycleId, selection.primaryId(),
                json(selection.assistantIds()), json(affected), TextNormalizer.required(body.reason()),
                cycle.getVersion(), actor.membershipId());
        recordEvent(actor, cycle, "PARTICIPANT_CHANGE_REQUESTED", cycle.getStatus(), cycle.getStatus(), Map.of(
                "requestId", requestId.toString(), "affectedMembershipIds",
                affected.stream().map(UUID::toString).toList()));
        audit.record(actor, "CUSTOMER_BUSINESS_PARTICIPANT_CHANGE_REQUESTED", "CUSTOMER_BUSINESS_CYCLE",
                cycleId.toString(), "SUCCESS", Map.of("requestId", requestId.toString()));
        notifications.publish(actor.organizationId(), affected, "BUSINESS_PARTICIPANT_CHANGE",
                "主辅名单变更待确认", "一项正式报价后的主辅名单变更需要你的确认。",
                "/customers/" + cycle.getCustomerId(), "participant-change:" + requestId);
        return participantChange(actor.organizationId(), requestId);
    }

    @Transactional
    public BusinessCycleDtos.ParticipantChangeResponse confirmParticipantChange(
            UUID customerId, UUID cycleId, UUID requestId) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        BusinessCycleEntity cycle = requireOrganizationCycle(cycleId, actor);
        if (!cycle.getCustomerId().equals(customerId)) {
            throw ApiException.notFound("BUSINESS_CYCLE_NOT_FOUND", "业务周期不存在");
        }
        BusinessCycleDtos.ParticipantChangeResponse request = participantChange(actor.organizationId(), requestId);
        if (!request.businessCycleId().equals(cycleId) || !"PENDING".equals(request.status())) {
            throw ApiException.conflict("PARTICIPANT_CHANGE_NOT_PENDING", "该主辅变更申请已不再等待确认");
        }
        if (!request.affectedMembershipIds().contains(actor.membershipId())) {
            throw ApiException.forbidden("只有受影响成员本人可以确认主辅变更");
        }
        jdbc.update("""
                insert into customer_business_participant_change_approvals(request_id,membership_id)
                values (?,?) on conflict do nothing
                """, requestId, actor.membershipId());
        jdbc.update("""
                update customer_business_participant_change_requests request set status='READY',version=version+1
                where request.id=? and request.status='PENDING'
                  and (select count(*) from customer_business_participant_change_approvals approval
                       where approval.request_id=request.id)=jsonb_array_length(request.affected_membership_ids)
                """, requestId);
        audit.record(actor, "CUSTOMER_BUSINESS_PARTICIPANT_CHANGE_CONFIRMED", "CUSTOMER_BUSINESS_CYCLE",
                cycleId.toString(), "SUCCESS", Map.of("requestId", requestId.toString()));
        return participantChange(actor.organizationId(), requestId);
    }

    @Transactional
    public BusinessCycleDtos.Response applyParticipantChange(UUID cycleId, UUID requestId) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        BusinessCycleEntity cycle = requireOrganizationCycle(cycleId, actor);
        BusinessCycleDtos.ParticipantChangeResponse request = participantChangeForUpdate(
                actor.organizationId(), requestId);
        if (!request.businessCycleId().equals(cycleId) || !"READY".equals(request.status())) {
            throw ApiException.conflict("PARTICIPANT_CHANGE_NOT_READY", "受影响成员尚未全部确认或申请已结束");
        }
        requireVersion(cycle, request.expectedCycleVersion());
        ParticipantSelection selection = validateParticipants(actor, request.proposedPrimaryMembershipId(),
                request.proposedAssistantMembershipIds());
        int snapshotVersion = cycle.reviseParticipantSnapshot(
                isCrossSale(actor.organizationId(), cycle.getCustomerId()));
        cycles.saveAndFlush(cycle);
        saveParticipantSnapshot(actor, cycle, snapshotVersion, selection, request.reason());
        jdbc.update("""
                update customer_business_participant_change_requests
                set status='APPLIED',applied_at=now(),version=version+1 where id=? and status='READY'
                """, requestId);
        recordEvent(actor, cycle, "PARTICIPANTS_REVISED", cycle.getStatus(), cycle.getStatus(), Map.of(
                "requestId", requestId.toString(), "snapshotVersion", snapshotVersion));
        audit.record(actor, "CUSTOMER_BUSINESS_PARTICIPANTS_REVISED", "CUSTOMER_BUSINESS_CYCLE",
                cycleId.toString(), "SUCCESS", Map.of("requestId", requestId.toString(),
                        "snapshotVersion", snapshotVersion));
        return response(cycle, customers.requireVisibleEntity(cycle.getCustomerId(), actor).getFullName(), true);
    }

    @Transactional
    public BusinessCycleDtos.Response correctStatus(UUID cycleId, BusinessCycleDtos.CorrectStatusRequest body) {
        LegacyDealFeatures.requireWritable();
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        BusinessCycleEntity cycle = requireOrganizationCycle(cycleId, actor);
        CustomerEntity customer = customers.requireVisibleEntity(cycle.getCustomerId(), actor);
        requireVersion(cycle, body.expectedVersion());
        if (!Set.of(BusinessCycleStatus.LOST_TO_COMPETITOR, BusinessCycleStatus.CANCELLED)
                .contains(cycle.getStatus()) || body.targetStatus() != BusinessCycleStatus.IN_PROGRESS) {
            throw ApiException.badRequest("INVALID_BUSINESS_STATUS_CORRECTION",
                    "管理员只能将友商流失或已取消纠正为进行中；成单必须通过正式确认流程");
        }
        membershipDirectory.lockCustomer(actor.organizationId(), cycle.getCustomerId());
        if (cycles.existsByOrganizationIdAndCustomerIdAndStatusIn(
                actor.organizationId(), cycle.getCustomerId(), ACTIVE_STATUSES)) {
            throw ApiException.conflict("ACTIVE_BUSINESS_CYCLE_EXISTS", "该客户已有其他进行中的业务周期");
        }
        BusinessCycleStatus previous = cycle.getStatus();
        cycle.correctStatus(body.targetStatus());
        cycles.saveAndFlush(cycle);
        recordEvent(actor, cycle, "STATUS_CORRECTED", previous, cycle.getStatus(),
                Map.of("reason", TextNormalizer.required(body.reason())));
        audit.record(actor, "CUSTOMER_BUSINESS_STATUS_CORRECTED", "CUSTOMER_BUSINESS_CYCLE",
                cycle.getId().toString(), "SUCCESS", Map.of("previousStatus", previous.name(),
                        "newStatus", cycle.getStatus().name(), "reason", TextNormalizer.required(body.reason())));
        return response(cycle, customer.getFullName(), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<BusinessCycleDtos.Response> adminSearch(
            Boolean crossSale, UUID primaryMembershipId, UUID assistantMembershipId,
            BusinessCycleStatus status, Instant markedFrom, Instant markedTo, String quality,
            int page, int size) {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin() && !actor.isTeamManager()) {
            throw ApiException.forbidden("只有组织管理员或团队长可以查看业务周期汇总");
        }
        validateQuality(quality);
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "statusMarkedAt").and(Sort.by(Sort.Direction.DESC, "createdAt")));
        var specification = BusinessCycleSpecifications.adminMatches(actor.organizationId(), crossSale,
                primaryMembershipId, assistantMembershipId, status, markedFrom, markedTo, quality);
        if (actor.isTeamManager()) {
            specification = specification.and(BusinessCycleSpecifications.customerTeamIn(actor.managedTeamIds()));
        }
        var result = cycles.findAll(specification, pageable);
        return PageResponse.from(result.map(cycle -> response(cycle, cycle.getCustomer().getFullName(), false)));
    }

    @Transactional
    public BusinessCycleDtos.ReferralResponse createReferral(
            UUID referrerCustomerId, BusinessCycleDtos.CreateReferralRequest body) {
        CurrentActor actor = actors.require();
        permissions.requireCustomerWrite(actor);
        CustomerEntity referrer = requireWritableCustomer(referrerCustomerId, actor);
        String idempotencyHash = hash(body.idempotencyKey());
        var existing = referrals.findByOrganizationIdAndCreatedByMembershipIdAndIdempotencyKeyHash(
                actor.organizationId(), actor.membershipId(), idempotencyHash);
        if (existing.isPresent()) {
            if (!existing.get().getReferrerCustomerId().equals(referrerCustomerId)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "幂等键已用于其他转介绍");
            }
            return referralResponse(existing.get(), customers.get(existing.get().getReferredCustomerId()));
        }
        if (body.sourceBusinessCycleId() != null) {
            BusinessCycleEntity source = requireOrganizationCycle(body.sourceBusinessCycleId(), actor);
            if (!source.getCustomerId().equals(referrerCustomerId)) {
                throw ApiException.badRequest("REFERRAL_SOURCE_CYCLE_MISMATCH", "来源业务周期不属于转介绍客户");
            }
        }
        CustomerDtos.Response referred = customers.create(body.customer());
        CustomerReferralEntity referral = referrals.save(new CustomerReferralEntity(
                actor.organizationId(), referrerCustomerId, referred.id(), body.sourceBusinessCycleId(),
                TextNormalizer.optional(body.relationshipNote()), actor.membershipId(), idempotencyHash));
        audit.record(actor, "CUSTOMER_REFERRAL_CREATED", "CUSTOMER_REFERRAL", referral.getId().toString(),
                "SUCCESS", Map.of("referrerCustomerId", referrer.getId().toString(),
                        "referredCustomerId", referred.id().toString()));
        return referralResponse(referral, referred);
    }

    @Transactional(readOnly = true)
    public List<BusinessCycleDtos.ReferralResponse> listReferrals(UUID referrerCustomerId) {
        CurrentActor actor = actors.require();
        customers.requireVisibleEntity(referrerCustomerId, actor);
        return referrals.findByOrganizationIdAndReferrerCustomerIdOrderByCreatedAtDesc(
                        actor.organizationId(), referrerCustomerId).stream()
                .map(referral -> referralResponse(referral, customers.get(referral.getReferredCustomerId())))
                .toList();
    }

    private CustomerEntity requireWritableCustomer(UUID customerId, CurrentActor actor) {
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        customers.requireTimelineEditable(customer, actor);
        if (customer.getArchivedAt() != null) {
            throw ApiException.conflict("ARCHIVED_CUSTOMER_BUSINESS_FORBIDDEN", "归档客户不能修改业务周期");
        }
        return customer;
    }

    private VisibleCycle requireVisibleCycle(UUID customerId, UUID cycleId, CurrentActor actor) {
        CustomerEntity customer = customers.requireVisibleEntity(customerId, actor);
        BusinessCycleEntity cycle = requireOrganizationCycle(cycleId, actor);
        if (!cycle.getCustomerId().equals(customerId)) {
            throw ApiException.notFound("BUSINESS_CYCLE_NOT_FOUND", "业务周期不存在");
        }
        return new VisibleCycle(cycle, customer);
    }

    private VisibleCycle requireWritableCycle(UUID customerId, UUID cycleId, CurrentActor actor) {
        VisibleCycle visible = requireVisibleCycle(customerId, cycleId, actor);
        customers.requireTimelineEditable(visible.customer(), actor);
        if (visible.customer().getArchivedAt() != null) {
            throw ApiException.conflict("ARCHIVED_CUSTOMER_BUSINESS_FORBIDDEN", "归档客户不能修改业务周期");
        }
        return visible;
    }

    private BusinessCycleEntity requireOrganizationCycle(UUID cycleId, CurrentActor actor) {
        return cycles.findByIdAndOrganizationId(cycleId, actor.organizationId())
                .orElseThrow(() -> ApiException.notFound("BUSINESS_CYCLE_NOT_FOUND", "业务周期不存在"));
    }

    private ParticipantSelection validateParticipants(CurrentActor actor, UUID primaryMembershipId,
                                                      List<UUID> requestedAssistants) {
        List<UUID> assistants = requestedAssistants == null ? List.of() : requestedAssistants;
        LinkedHashSet<UUID> distinctAssistants = new LinkedHashSet<>(assistants);
        if (distinctAssistants.size() != assistants.size() || distinctAssistants.size() > 2) {
            throw ApiException.badRequest("INVALID_BUSINESS_ASSISTANTS", "辅助人员最多2名且不能重复");
        }
        if (distinctAssistants.contains(primaryMembershipId)) {
            throw ApiException.badRequest("INVALID_BUSINESS_ASSISTANTS", "主负责人不能同时作为辅助人员");
        }
        LinkedHashSet<UUID> all = new LinkedHashSet<>();
        all.add(primaryMembershipId);
        all.addAll(distinctAssistants);
        Map<UUID, String> names = membershipDirectory.requireEligibleMembers(actor.organizationId(), all);
        return new ParticipantSelection(primaryMembershipId, List.copyOf(distinctAssistants), names);
    }

    private void saveParticipantSnapshot(CurrentActor actor, BusinessCycleEntity cycle, int snapshotVersion,
                                         ParticipantSelection selection, String reason) {
        List<BusinessCycleParticipantSnapshotEntity> rows = new ArrayList<>();
        rows.add(new BusinessCycleParticipantSnapshotEntity(actor.organizationId(), cycle.getId(), snapshotVersion,
                selection.primaryId(), BusinessParticipantRole.PRIMARY,
                selection.names().get(selection.primaryId()), actor.membershipId(), reason));
        for (UUID assistantId : selection.assistantIds()) {
            rows.add(new BusinessCycleParticipantSnapshotEntity(actor.organizationId(), cycle.getId(), snapshotVersion,
                    assistantId, BusinessParticipantRole.ASSISTANT, selection.names().get(assistantId),
                    actor.membershipId(), reason));
        }
        snapshots.saveAll(rows);
        snapshots.flush();
    }

    private void requireVersion(BusinessCycleEntity cycle, long expectedVersion) {
        if (cycle.getVersion() != expectedVersion) {
            throw ApiException.conflict("STALE_BUSINESS_CYCLE", "业务周期已被其他成员更新，请刷新后重试");
        }
    }

    private void requireStatus(BusinessCycleEntity cycle, BusinessCycleStatus expected, String message) {
        if (cycle.getStatus() != expected) {
            throw ApiException.conflict("INVALID_BUSINESS_CYCLE_STATUS", message);
        }
    }

    private void requireOpenForMemberResult(BusinessCycleEntity cycle) {
        if (!ACTIVE_STATUSES.contains(cycle.getStatus())) {
            throw ApiException.conflict("INVALID_BUSINESS_CYCLE_STATUS", "当前业务周期已经结束，不能重复标记结果");
        }
    }

    private boolean isAClass(CustomerEntity customer) {
        return customer.getTags().stream().anyMatch(tag -> tag.isActive()
                && CustomerQuality.CATEGORY.equals(tag.getCategory()) && "A类用户".equals(tag.getName()));
    }

    private void validateQuality(String quality) {
        if (quality == null || quality.isBlank()) return;
        boolean valid = CustomerQuality.LEVELS.stream().anyMatch(level -> level.code().equalsIgnoreCase(quality.trim())
                || level.label().equalsIgnoreCase(quality.trim()));
        if (!valid) throw ApiException.badRequest("INVALID_CUSTOMER_QUALITY", "客户等级只支持 A、B、C");
    }

    private void recordEvent(CurrentActor actor, BusinessCycleEntity cycle, String action,
                             BusinessCycleStatus previous, BusinessCycleStatus next, Map<String, Object> details) {
        events.save(new BusinessCycleEventEntity(actor.organizationId(), cycle.getId(), actor.membershipId(), action,
                previous, next, details));
    }

    private void publish(CurrentActor actor, BusinessCycleEntity cycle, ParticipantSelection selection,
                         String eventType, String title, String summary, String dedupeKey) {
        LinkedHashSet<UUID> explicit = new LinkedHashSet<>();
        explicit.add(actor.membershipId());
        if (selection != null) {
            explicit.add(selection.primaryId());
            explicit.addAll(selection.assistantIds());
        }
        notifications.publish(actor.organizationId(), membershipDirectory.notificationRecipients(
                        actor.organizationId(), cycle.getCustomerId(), explicit), eventType, title, summary,
                "/customers/" + cycle.getCustomerId(), dedupeKey);
    }

    private BusinessCycleDtos.Response response(BusinessCycleEntity cycle, String customerName, boolean history) {
        List<BusinessCycleParticipantSnapshotEntity> participantRows = cycle.getCurrentSnapshotVersion() == 0
                ? List.of()
                : snapshots.findByBusinessCycleIdAndSnapshotVersionOrderByRoleTypeAscDisplayNameSnapshotAsc(
                        cycle.getId(), cycle.getCurrentSnapshotVersion());
        List<BusinessCycleEventEntity> eventRows = history
                ? events.findByBusinessCycleIdOrderByCreatedAtAscIdAsc(cycle.getId()) : List.of();
        return BusinessCycleDtos.Response.from(cycle, customerName, businessQuality(cycle.getCustomerId()),
                isCrossSale(cycle.getOrganizationId(), cycle.getCustomerId()), participantRows, eventRows);
    }

    private boolean isCrossSale(UUID organizationId, UUID customerId) {
        Boolean value = jdbc.queryForObject("""
                select exists (
                  select 1 from customer_access_grants grant_row
                  join memberships collaborator_membership
                    on collaborator_membership.id=grant_row.grantee_membership_id
                   and collaborator_membership.organization_id=grant_row.organization_id
                   and collaborator_membership.status='ACTIVE'
                  join user_accounts collaborator_account
                    on collaborator_account.id=collaborator_membership.user_id
                   and collaborator_account.enabled=true
                  join customers customer
                    on customer.id=grant_row.customer_id
                   and customer.organization_id=grant_row.organization_id
                  where grant_row.organization_id=? and grant_row.customer_id=?
                    and collaborator_membership.id<>customer.owner_membership_id
                )
                """, Boolean.class, organizationId, customerId);
        return Boolean.TRUE.equals(value);
    }

    private String businessQuality(UUID customerId) {
        return jdbc.query("""
                select tag.name from customer_tags link join tags tag on tag.id=link.tag_id
                where link.customer_id=? and tag.category=? and tag.active=true
                order by case tag.name when 'A类用户' then 1 when 'B类用户' then 2 else 3 end limit 1
                """, (row, index) -> row.getString("name"), customerId, CustomerQuality.CATEGORY)
                .stream().findFirst().map(value -> value.substring(0, 1)).orElse(null);
    }

    private BusinessCycleDtos.ReferralResponse referralResponse(
            CustomerReferralEntity referral, CustomerDtos.Response customer) {
        return new BusinessCycleDtos.ReferralResponse(referral.getId(), referral.getReferrerCustomerId(),
                referral.getReferredCustomerId(), referral.getSourceBusinessCycleId(),
                referral.getRelationshipNote(), referral.getCreatedAt(), customer);
    }

    private BusinessCycleDtos.ParticipantChangeResponse participantChange(UUID organizationId, UUID requestId) {
        return participantChange(organizationId, requestId, false);
    }

    private BusinessCycleDtos.ParticipantChangeResponse participantChangeForUpdate(
            UUID organizationId, UUID requestId) {
        return participantChange(organizationId, requestId, true);
    }

    private BusinessCycleDtos.ParticipantChangeResponse participantChange(
            UUID organizationId, UUID requestId, boolean lock) {
        String sql = """
                select request.id,request.business_cycle_id,request.proposed_primary_membership_id,
                       request.proposed_assistant_membership_ids::text assistants,
                       request.affected_membership_ids::text affected,request.reason,request.status,
                       request.expected_cycle_version,request.created_at,request.applied_at,
                       coalesce((select jsonb_agg(approval.membership_id)
                         from customer_business_participant_change_approvals approval
                         where approval.request_id=request.id),'[]'::jsonb)::text approved
                from customer_business_participant_change_requests request
                where request.organization_id=? and request.id=?
                """ + (lock ? " for update" : "");
        return jdbc.query(sql, (row, index) -> participantChangeRow(row), organizationId, requestId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("PARTICIPANT_CHANGE_NOT_FOUND", "主辅变更申请不存在"));
    }

    private List<BusinessCycleDtos.ParticipantChangeResponse> participantChanges(
            UUID organizationId, UUID cycleId, boolean pendingOnly) {
        StringBuilder sql = new StringBuilder("""
                select request.id,request.business_cycle_id,request.proposed_primary_membership_id,
                       request.proposed_assistant_membership_ids::text assistants,
                       request.affected_membership_ids::text affected,request.reason,request.status,
                       request.expected_cycle_version,request.created_at,request.applied_at,
                       coalesce((select jsonb_agg(approval.membership_id)
                         from customer_business_participant_change_approvals approval
                         where approval.request_id=request.id),'[]'::jsonb)::text approved
                from customer_business_participant_change_requests request
                where request.organization_id=?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(organizationId);
        if (cycleId != null) {
            sql.append(" and request.business_cycle_id=?");
            arguments.add(cycleId);
        }
        if (pendingOnly) sql.append(" and request.status in ('PENDING','READY')");
        sql.append(" order by request.created_at desc");
        return jdbc.query(sql.toString(), (row, index) -> participantChangeRow(row), arguments.toArray());
    }

    private BusinessCycleDtos.ParticipantChangeResponse participantChangeRow(ResultSet row) throws SQLException {
        return new BusinessCycleDtos.ParticipantChangeResponse(
                row.getObject("id", UUID.class), row.getObject("business_cycle_id", UUID.class),
                row.getObject("proposed_primary_membership_id", UUID.class),
                readUuidList(row.getString("assistants")), new LinkedHashSet<>(readUuidList(row.getString("affected"))),
                new LinkedHashSet<>(readUuidList(row.getString("approved"))), row.getString("reason"),
                row.getString("status"), row.getLong("expected_cycle_version"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("applied_at") == null ? null : row.getTimestamp("applied_at").toInstant());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化主辅变更数据", exception);
        }
    }

    private List<UUID> readUuidList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取主辅变更数据", exception);
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(TextNormalizer.required(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record VisibleCycle(BusinessCycleEntity cycle, CustomerEntity customer) {}
    private record ParticipantSelection(UUID primaryId, List<UUID> assistantIds, Map<UUID, String> names) {}
}
