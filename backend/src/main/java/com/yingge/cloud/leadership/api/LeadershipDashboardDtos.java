package com.yingge.cloud.leadership.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LeadershipDashboardDtos {
    private LeadershipDashboardDtos() {}

    public record TeamOption(UUID id, String name) {}
    public record MemberOption(UUID membershipId, String displayName, String username, String teamNames) {}

    public record Headline(
            long totalCustomers,
            long addedFriendCustomers,
            long trustedFriendCustomers,
            long qualifiedFriendCustomers,
            long todayNewCustomers,
            long todayQualifiedCustomers,
            double addedRate,
            double trustRate,
            double qualifiedRate
    ) {}

    public record CustomerActivity(
            UUID customerId,
            String fullName,
            String ownerName,
            String teamName,
            String currentStatus,
            String intentionLevel,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record MemberRanking(
            UUID membershipId,
            String displayName,
            String teamNames,
            long totalCustomers,
            long addedFriendCustomers,
            long trustedFriendCustomers,
            long qualifiedFriendCustomers,
            long qualityACustomers,
            long todayCustomers,
            long assignedTasks,
            long completedTasks,
            double taskCompletionRate
    ) {}

    public record DailyConversion(
            LocalDate date,
            long addedFriendCustomers,
            long trustedFriendCustomers,
            long qualifiedFriendCustomers,
            long cumulativeQualifiedCustomers
    ) {}

    public record TaskSummary(
            long assignmentCount,
            long pendingCount,
            long inProgressCount,
            long submittedCount,
            long completedCount,
            long returnedCount,
            long overdueCount,
            double completionRate
    ) {}

    public record TaskProgress(
            UUID taskId,
            String title,
            Instant dueAt,
            String status,
            long assignmentCount,
            long completedCount,
            long submittedCount,
            double completionRate,
            boolean overdue
    ) {}

    public record TaskPublication(
            UUID taskId,
            String title,
            String distributionType,
            Instant startAt,
            Instant dueAt,
            Instant createdAt,
            String publisherName,
            long assignmentCount
    ) {}

    public record SourceSlice(
            String source,
            long customerCount,
            long addedFriendCustomers,
            long trustedFriendCustomers,
            long qualifiedFriendCustomers,
            double share,
            double addedRate,
            double trustRate,
            double qualifiedRate
    ) {}

    public record CustomerQualitySlice(
            String code,
            String label,
            String description,
            String color,
            long customerCount,
            double share
    ) {}

    public record CustomerQualitySummary(
            long classifiedCustomers,
            long unclassifiedCustomers,
            List<CustomerQualitySlice> levels
    ) {}

    public record DisplayMessage(
            UUID id,
            String title,
            String content,
            String priority,
            Instant publishedAt
    ) {}

    public record StaleOwner(
            UUID membershipId,
            String displayName,
            long customerCount
    ) {}

    public record AClassStaleSummary(
            boolean enabled,
            int thresholdHours,
            long customerCount,
            long unassignedCount,
            List<StaleOwner> owners,
            Instant calculatedAt
    ) {}

    public record SchoolComparison(
            String featuredGrade,
            long jiangsuUniversity,
            long jiangsuUniversityOfScienceAndTechnology,
            long difference,
            String leader
    ) {}

    public record TickerItem(
            String id,
            String type,
            String priority,
            String text
    ) {}

    public record Overview(
            Instant generatedAt,
            Instant lastBusinessActivityAt,
            LocalDate startDate,
            LocalDate endDate,
            int days,
            boolean administrator,
            UUID selectedTeamId,
            UUID selectedMembershipId,
            List<TeamOption> teams,
            List<MemberOption> members,
            Headline headline,
            List<CustomerActivity> recentCustomerActivities,
            List<MemberRanking> rankings,
            List<DailyConversion> dailyConversions,
            CustomerQualitySummary customerQuality,
            TaskSummary taskSummary,
            List<TaskProgress> tasks,
            List<TaskPublication> recentTaskPublications,
            List<SourceSlice> sources,
            List<DisplayMessage> displayMessages,
            String featuredGrade,
            AClassStaleSummary aClassStale,
            SchoolComparison schoolComparison,
            List<TickerItem> tickerItems,
            LeadershipScreenSettingsDtos.Settings screenSettings
    ) {}
}
