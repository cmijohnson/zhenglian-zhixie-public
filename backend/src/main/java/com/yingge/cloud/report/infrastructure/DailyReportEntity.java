package com.yingge.cloud.report.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "daily_reports")
public class DailyReportEntity {
    @Id
    private UUID id;
    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;
    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;
    @Column(name = "report_date", nullable = false)
    private LocalDate reportDate;
    @Column(name = "deep_communication_count", nullable = false)
    private int deepCommunicationCount;
    @Column(name = "today_issues", nullable = false)
    private String todayIssues;
    @Column(name = "tomorrow_plan", nullable = false)
    private String tomorrowPlan;
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected DailyReportEntity() {}

    public DailyReportEntity(UUID organizationId, UUID membershipId, LocalDate reportDate,
                             int deepCommunicationCount, String todayIssues, String tomorrowPlan) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.organizationId = organizationId;
        this.membershipId = membershipId;
        this.reportDate = reportDate;
        apply(deepCommunicationCount, todayIssues, tomorrowPlan, now);
        this.submittedAt = now;
    }

    public void update(int deepCommunicationCount, String todayIssues, String tomorrowPlan) {
        apply(deepCommunicationCount, todayIssues, tomorrowPlan, Instant.now());
    }

    private void apply(int deepCommunicationCount, String todayIssues, String tomorrowPlan, Instant now) {
        this.deepCommunicationCount = deepCommunicationCount;
        this.todayIssues = todayIssues;
        this.tomorrowPlan = tomorrowPlan;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public UUID getMembershipId() { return membershipId; }
    public LocalDate getReportDate() { return reportDate; }
    public int getDeepCommunicationCount() { return deepCommunicationCount; }
    public String getTodayIssues() { return todayIssues; }
    public String getTomorrowPlan() { return tomorrowPlan; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
