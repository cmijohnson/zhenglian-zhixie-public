package com.yingge.cloud.reminder.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "reminders")
public class ReminderEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "customer_id", nullable = false)
    private UUID customerId;
    @Column(name = "interaction_id")
    private UUID interactionId;
    @Column(name = "assigned_membership_id", nullable = false)
    private UUID assignedMembershipId;
    @Column(name = "remind_at", nullable = false)
    private Instant remindAt;
    @Column(nullable = false)
    private String title;
    @Column(nullable = false)
    private String status;
    @Column(name = "delivery_status", nullable = false)
    private String deliveryStatus;
    @Column(name = "reminder_type", nullable = false)
    private String reminderType;
    @Column(name = "automatic_day")
    private LocalDate automaticDay;
    @Column(name = "related_resource_id")
    private UUID relatedResourceId;
    @Column(name = "source_analysis_version_id")
    private UUID sourceAnalysisVersionId;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected ReminderEntity() {
    }

    public ReminderEntity(UUID organizationId, UUID customerId, UUID interactionId,
                          UUID assignedMembershipId, Instant remindAt, String title) {
        this(organizationId, customerId, interactionId, assignedMembershipId, remindAt, title, null);
    }

    public ReminderEntity(UUID organizationId, UUID customerId, UUID interactionId,
                          UUID assignedMembershipId, Instant remindAt, String title,
                          UUID sourceAnalysisVersionId) {
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.customerId = customerId;
        this.interactionId = interactionId;
        this.assignedMembershipId = assignedMembershipId;
        this.remindAt = remindAt;
        this.title = title;
        this.status = "PENDING";
        this.deliveryStatus = "UNSENT";
        this.reminderType = "MANUAL";
        this.sourceAnalysisVersionId = sourceAnalysisVersionId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(Instant remindAt, String title) {
        if (remindAt != null) this.remindAt = remindAt;
        if (title != null && !title.isBlank()) this.title = title.trim();
        this.updatedAt = Instant.now();
    }

    public void changeStatus(String status) {
        if (!java.util.Set.of("PENDING", "COMPLETED", "CANCELLED").contains(status)) {
            throw com.yingge.cloud.shared.api.ApiException.badRequest("INVALID_REMINDER_STATUS", "提醒状态无效");
        }
        this.status = status;
        this.completedAt = "COMPLETED".equals(status) ? Instant.now() : null;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public UUID getInteractionId() { return interactionId; }
    public UUID getAssignedMembershipId() { return assignedMembershipId; }
    public Instant getRemindAt() { return remindAt; }
    public String getTitle() { return title; }
    public String getStatus() { return status; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public String getReminderType() { return reminderType; }
    public LocalDate getAutomaticDay() { return automaticDay; }
    public UUID getRelatedResourceId() { return relatedResourceId; }
    public UUID getSourceAnalysisVersionId() { return sourceAnalysisVersionId; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
