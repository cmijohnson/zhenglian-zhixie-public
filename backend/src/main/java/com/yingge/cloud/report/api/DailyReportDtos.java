package com.yingge.cloud.report.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class DailyReportDtos {
    private DailyReportDtos() {}

    public record UpsertRequest(
            @PositiveOrZero int deepCommunicationCount,
            @NotBlank @Size(max = 5000) String todayIssues,
            @NotBlank @Size(max = 5000) String tomorrowPlan
    ) {}

    public record Response(
            UUID id,
            UUID membershipId,
            String memberDisplayName,
            LocalDate reportDate,
            long grade26NewValidLeads,
            int deepCommunicationCount,
            int pendingDeepCommunicationCount,
            boolean performanceManaged,
            String todayIssues,
            String tomorrowPlan,
            Instant submittedAt,
            Instant updatedAt,
            long version,
            boolean submitted
    ) {}

    public record CalendarMember(
            UUID membershipId,
            String displayName
    ) {}

    public record CalendarDay(
            LocalDate date,
            boolean reportingDay,
            int expectedCount,
            int submittedCount,
            List<CalendarMember> submittedMembers,
            List<CalendarMember> missingMembers
    ) {}

    public record CalendarResponse(
            String month,
            LocalDate today,
            int expectedMemberCount,
            List<CalendarDay> days
    ) {}
}
