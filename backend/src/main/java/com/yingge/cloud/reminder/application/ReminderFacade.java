package com.yingge.cloud.reminder.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.intelligence.infrastructure.CustomerIntelligenceStore;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.reminder.api.ReminderDtos;
import com.yingge.cloud.reminder.infrastructure.ReminderEntity;
import com.yingge.cloud.reminder.infrastructure.ReminderRepository;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReminderFacade {
    private final ReminderRepository reminders;
    private final CustomerService customers;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;
    private final CustomerIntelligenceStore intelligence;

    public ReminderFacade(ReminderRepository reminders, CustomerService customers, CurrentActorProvider actors,
                          PermissionService permissions, AuditService audit,
                          CustomerIntelligenceStore intelligence) {
        this.reminders = reminders;
        this.customers = customers;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
        this.intelligence = intelligence;
    }

    @Transactional(readOnly = true)
    public List<ReminderDtos.Response> list() {
        var actor = actors.require();
        var ids = customers.visibleEntities(actor, false).stream().map(row -> row.getId()).toList();
        if (ids.isEmpty()) return List.of();
        return reminders.findByCustomerIdInOrderByRemindAtAsc(ids).stream().map(ReminderDtos.Response::from).toList();
    }

    @Transactional(readOnly = true)
    public List<ReminderDtos.Response> urgentAClassStale() {
        var actor = actors.require();
        return reminders
                .findByOrganizationIdAndAssignedMembershipIdAndReminderTypeAndStatusOrderByRemindAtAsc(
                        actor.organizationId(), actor.membershipId(), "A_CLASS_STALE", "PENDING")
                .stream().map(ReminderDtos.Response::from).toList();
    }

    @Transactional
    public ReminderDtos.Response create(ReminderDtos.CreateRequest body) {
        var actor = actors.require();
        permissions.requireCustomerWrite(actor);
        var customer = customers.requireVisibleEntity(body.customerId(), actor);
        customers.requireTimelineEditable(customer, actor);
        requireActiveCustomer(customer);
        if (body.sourceAnalysisVersionId() != null && !intelligence.isSuccessfulVersionForCustomer(
                actor.organizationId(), customer.getId(), body.sourceAnalysisVersionId())) {
            throw ApiException.badRequest("SOURCE_ANALYSIS_VERSION_INVALID",
                    "来源分析版本不存在或不属于当前客户");
        }
        var saved = reminders.save(new ReminderEntity(actor.organizationId(), customer.getId(), null,
                customer.getOwnerMembershipId(), body.remindAt(), body.title().trim(),
                body.sourceAnalysisVersionId()));
        Map<String, Object> details = body.sourceAnalysisVersionId() == null ? null : Map.of(
                "customerId", customer.getId().toString(),
                "sourceAnalysisVersionId", body.sourceAnalysisVersionId().toString());
        audit.record(actor, "REMINDER_CREATED", "REMINDER", saved.getId().toString(), "SUCCESS", details);
        return ReminderDtos.Response.from(saved);
    }

    @Transactional
    public ReminderDtos.Response update(UUID id, ReminderDtos.UpdateRequest body) {
        var actor = actors.require();
        permissions.requireCustomerWrite(actor);
        var reminder = reminders.findById(id).orElseThrow(() -> ApiException.notFound("REMINDER_NOT_FOUND", "提醒不存在"));
        var customer = customers.requireVisibleEntity(reminder.getCustomerId(), actor);
        customers.requireTimelineEditable(customer, actor);
        requireActiveCustomer(customer);
        reminder.update(body.remindAt(), body.title());
        if (body.status() != null) reminder.changeStatus(body.status());
        audit.record(actor, "REMINDER_UPDATED", "REMINDER", id.toString(), "SUCCESS", null);
        return ReminderDtos.Response.from(reminder);
    }

    private void requireActiveCustomer(com.yingge.cloud.customer.infrastructure.CustomerEntity customer) {
        if (customer.getArchivedAt() != null) {
            throw ApiException.conflict(
                    "ARCHIVED_CUSTOMER_REMINDER_FORBIDDEN",
                    "归档客户不能创建或修改提醒，请先恢复客户");
        }
    }
}
