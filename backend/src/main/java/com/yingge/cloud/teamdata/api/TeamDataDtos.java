package com.yingge.cloud.teamdata.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TeamDataDtos {
    private TeamDataDtos() {}

    public record TeamOption(UUID id, String name) {}
    public record MemberOption(UUID membershipId, String displayName, String username, String teamNames) {}
    public record Stats(long memberCount, long activeCustomerCount, long todayNewCustomerCount,
                        long todayGrade26LeadCount, long pendingLeadReviewCount, long todayReportCount,
                        long xiaohongshuValidCount, long douyinValidCount) {}
    public record ReportItem(UUID id, UUID membershipId, String memberDisplayName, LocalDate reportDate,
                             long grade26NewValidLeads, int deepCommunicationCount,
                             String todayIssues, String tomorrowPlan, Instant updatedAt) {}
    public record ContentProgress(UUID membershipId, String displayName,
                                  long xiaohongshuValid, long douyinValid) {}
    public record LeadReviewItem(UUID customerId, String fullName, UUID ownerMembershipId,
                                 String ownerDisplayName, UUID createdByMembershipId,
                                 String creatorDisplayName, UUID teamId, String status,
                                 String reason, Instant createdAt, Instant reviewedAt) {}
    public record Overview(List<TeamOption> teams, List<MemberOption> members, Stats stats,
                           List<ReportItem> dailyReports, List<ContentProgress> contentProgress,
                           List<LeadReviewItem> leadReviewQueue,
                           UUID selectedTeamId, UUID selectedMembershipId, boolean administrator) {}
}
