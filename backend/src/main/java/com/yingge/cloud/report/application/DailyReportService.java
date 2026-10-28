package com.yingge.cloud.report.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.performance.application.PerformanceService;
import com.yingge.cloud.report.api.DailyReportDtos;
import com.yingge.cloud.report.infrastructure.DailyReportEntity;
import com.yingge.cloud.report.infrastructure.DailyReportRepository;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class DailyReportService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String HISTORY_SELECT = """
            select dr.id, dr.membership_id, u.display_name, dr.report_date,
                   case when exists (
                       select 1 from performance_rule_versions rule
                       where rule.organization_id=dr.organization_id and rule.effective_from<=dr.report_date
                         and exists (select 1 from performance_participants participant
                                     where participant.organization_id=dr.organization_id
                                       and participant.membership_id=dr.membership_id
                                       and participant.starts_on<=dr.report_date
                                       and (participant.ends_on is null or participant.ends_on>=dr.report_date))
                   ) then (
                       select count(*) from performance_interaction_claims claim
                       where claim.organization_id=dr.organization_id and claim.membership_id=dr.membership_id
                         and claim.performance_date=dr.report_date and claim.status='APPROVED' and claim.approved_type='DEEP'
                   ) else dr.deep_communication_count end deep_communication_count,
                   (select count(*) from performance_interaction_claims claim
                    where claim.organization_id=dr.organization_id and claim.membership_id=dr.membership_id
                      and claim.performance_date=dr.report_date and claim.status='PENDING' and claim.requested_type='DEEP') pending_deep_count,
                   exists (select 1 from performance_rule_versions rule
                           where rule.organization_id=dr.organization_id and rule.effective_from<=dr.report_date
                             and exists (select 1 from performance_participants participant
                                         where participant.organization_id=dr.organization_id
                                           and participant.membership_id=dr.membership_id
                                           and participant.starts_on<=dr.report_date
                                           and (participant.ends_on is null or participant.ends_on>=dr.report_date))) performance_managed,
                   dr.today_issues, dr.tomorrow_plan,
                   dr.submitted_at, dr.updated_at, dr.version,
                   (select count(*) from customers c
                    where c.organization_id=dr.organization_id
                      and c.created_by_membership_id=dr.membership_id
                      and c.lead_review_status='VALID'
                      and c.archived_at is null
                      and c.created_at >= (dr.report_date::timestamp at time zone 'Asia/Shanghai')
                      and c.created_at < ((dr.report_date + 1)::timestamp at time zone 'Asia/Shanghai')) grade26_count
            from daily_reports dr
            join memberships m on m.id=dr.membership_id
            join user_accounts u on u.id=m.user_id
            """;

    private final DailyReportRepository reports;
    private final CurrentActorProvider actors;
    private final DailyReportProperties reportProperties;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final PerformanceService performance;

    public DailyReportService(DailyReportRepository reports, CurrentActorProvider actors,
                              DailyReportProperties reportProperties, NamedParameterJdbcTemplate jdbc,
                              AuditService audit, PerformanceService performance) {
        this.reports = reports;
        this.actors = actors;
        this.reportProperties = reportProperties;
        this.jdbc = jdbc;
        this.audit = audit;
        this.performance = performance;
    }

    @Transactional(readOnly = true)
    public DailyReportDtos.Response today() {
        CurrentActor actor = actors.require();
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        return reports.findByOrganizationIdAndMembershipIdAndReportDate(
                        actor.organizationId(), actor.membershipId(), today)
                .map(row -> response(row, actor.displayName(), autoLeadCount(actor.organizationId(), actor.membershipId(), today),
                        performance.performanceManaged(actor.organizationId(), actor.membershipId(), today),
                        performance.approvedDeepCount(actor.organizationId(), actor.membershipId(), today),
                        performance.pendingDeepCount(actor.organizationId(), actor.membershipId(), today)))
                .orElseGet(() -> new DailyReportDtos.Response(
                        null, actor.membershipId(), actor.displayName(), today,
                        autoLeadCount(actor.organizationId(), actor.membershipId(), today),
                        performance.performanceManaged(actor.organizationId(), actor.membershipId(), today)
                                ? performance.approvedDeepCount(actor.organizationId(), actor.membershipId(), today) : 0,
                        performance.pendingDeepCount(actor.organizationId(), actor.membershipId(), today),
                        performance.performanceManaged(actor.organizationId(), actor.membershipId(), today), "", "",
                        null, null, 0, false));
    }

    @Transactional
    public DailyReportDtos.Response submit(LocalDate date, DailyReportDtos.UpsertRequest body) {
        CurrentActor actor = actors.require();
        requireContributor(actor);
        validateDate(date);
        String issues = required(body.todayIssues(), "REPORT_ISSUES_REQUIRED", "今日问题不能为空");
        String plan = required(body.tomorrowPlan(), "REPORT_PLAN_REQUIRED", "明日计划不能为空");
        boolean performanceManaged = performance.performanceManaged(actor.organizationId(), actor.membershipId(), date);
        int deepCount = performanceManaged
                ? performance.approvedDeepCount(actor.organizationId(), actor.membershipId(), date)
                : body.deepCommunicationCount();
        DailyReportEntity report = reports.findByOrganizationIdAndMembershipIdAndReportDate(
                        actor.organizationId(), actor.membershipId(), date)
                .map(existing -> {
                    existing.update(deepCount, issues, plan);
                    return existing;
                })
                .orElseGet(() -> new DailyReportEntity(actor.organizationId(), actor.membershipId(), date,
                        deepCount, issues, plan));
        DailyReportEntity saved = reports.save(report);
        audit.record(actor, "DAILY_REPORT_SUBMITTED", "DAILY_REPORT", saved.getId().toString(), "SUCCESS",
                java.util.Map.of("reportDate", date.toString()));
        return response(saved, actor.displayName(), autoLeadCount(actor.organizationId(), actor.membershipId(), date),
                performanceManaged, deepCount,
                performance.pendingDeepCount(actor.organizationId(), actor.membershipId(), date));
    }

    @Transactional(readOnly = true)
    public List<DailyReportDtos.Response> history() {
        CurrentActor actor = actors.require();
        String scope;
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId())
                .addValue("membershipId", actor.membershipId());
        if (actor.isAdmin()) {
            scope = "";
        } else if (actor.isTeamManager() && !actor.managedTeamIds().isEmpty()) {
            scope = """
                    and (dr.membership_id=:membershipId or exists (
                        select 1 from team_members tm where tm.membership_id=dr.membership_id
                        and tm.team_id in (:teamIds)
                    ))
                    """;
            parameters.addValue("teamIds", actor.managedTeamIds());
        } else {
            scope = "and dr.membership_id=:membershipId";
        }
        return jdbc.query(HISTORY_SELECT + " where dr.organization_id=:organizationId " + scope +
                        " order by dr.report_date desc, u.display_name limit 100",
                parameters, (row, index) -> new DailyReportDtos.Response(
                        row.getObject("id", UUID.class), row.getObject("membership_id", UUID.class),
                        row.getString("display_name"), row.getObject("report_date", LocalDate.class),
                        row.getLong("grade26_count"), row.getInt("deep_communication_count"),
                        row.getInt("pending_deep_count"), row.getBoolean("performance_managed"),
                        row.getString("today_issues"), row.getString("tomorrow_plan"),
                        row.getTimestamp("submitted_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                        row.getLong("version"), true));
    }

    @Transactional(readOnly = true)
    public DailyReportDtos.CalendarResponse calendar(String requestedMonth) {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin() && !actor.isTeamManager()) {
            throw ApiException.forbidden("只有管理员和团队长可以查看日报提交日历");
        }
        YearMonth month;
        try {
            month = YearMonth.parse(requestedMonth);
        } catch (RuntimeException exception) {
            throw ApiException.badRequest("INVALID_REPORT_MONTH", "月份格式应为 YYYY-MM");
        }

        List<DailyReportDtos.CalendarMember> members = reportMembers(actor);
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        Map<LocalDate, Set<UUID>> submittedByDate = new LinkedHashMap<>();
        if (!members.isEmpty()) {
            List<UUID> memberIds = members.stream().map(DailyReportDtos.CalendarMember::membershipId).toList();
            jdbc.query("""
                    select dr.report_date, dr.membership_id
                    from daily_reports dr
                    where dr.organization_id=:organizationId
                      and dr.membership_id in (:memberIds)
                      and dr.report_date between :startDate and :endDate
                    """, new MapSqlParameterSource()
                    .addValue("organizationId", actor.organizationId())
                    .addValue("memberIds", memberIds)
                    .addValue("startDate", month.atDay(1))
                    .addValue("endDate", month.atEndOfMonth()), row -> {
                submittedByDate.computeIfAbsent(
                        row.getObject("report_date", LocalDate.class),
                        ignored -> new java.util.LinkedHashSet<>()
                ).add(row.getObject("membership_id", UUID.class));
            });
        }

        List<DailyReportDtos.CalendarDay> days = new ArrayList<>();
        for (int day = 1; day <= month.lengthOfMonth(); day++) {
            LocalDate date = month.atDay(day);
            boolean reportingDay = !date.isAfter(today)
                    && !date.isBefore(reportProperties.startDate());
            Set<UUID> submittedIds = submittedByDate.getOrDefault(date, Set.of());
            List<DailyReportDtos.CalendarMember> submittedMembers = members.stream()
                    .filter(member -> submittedIds.contains(member.membershipId()))
                    .toList();
            List<DailyReportDtos.CalendarMember> missingMembers = reportingDay
                    ? members.stream().filter(member -> !submittedIds.contains(member.membershipId())).toList()
                    : List.of();
            days.add(new DailyReportDtos.CalendarDay(
                    date, reportingDay, reportingDay ? members.size() : 0, submittedMembers.size(),
                    submittedMembers, missingMembers));
        }
        return new DailyReportDtos.CalendarResponse(
                month.toString(), today, members.size(), days);
    }

    private List<DailyReportDtos.CalendarMember> reportMembers(CurrentActor actor) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organizationId", actor.organizationId())
                .addValue("membershipId", actor.membershipId());
        String scope;
        if (actor.isAdmin()) {
            scope = "";
        } else if (!actor.managedTeamIds().isEmpty()) {
            scope = """
                    and (m.id=:membershipId or exists (
                        select 1 from team_members tm
                        where tm.membership_id=m.id and tm.team_id in (:teamIds)
                    ))
                    """;
            parameters.addValue("teamIds", actor.managedTeamIds());
        } else {
            scope = "and m.id=:membershipId";
        }
        return jdbc.query("""
                select m.id, u.display_name
                from memberships m
                join user_accounts u on u.id=m.user_id and u.enabled=true
                where m.organization_id=:organizationId and m.status='ACTIVE'
                  and exists (
                    select 1 from membership_roles mr
                    join roles r on r.id=mr.role_id
                    where mr.membership_id=m.id and r.code in ('MEMBER','TEAM_MANAGER')
                  )
                """ + scope + " order by u.display_name, m.id",
                parameters, (row, index) -> new DailyReportDtos.CalendarMember(
                        row.getObject("id", UUID.class), row.getString("display_name")));
    }

    private long autoLeadCount(UUID organizationId, UUID membershipId, LocalDate date) {
        Long count = jdbc.queryForObject("""
                select count(*) from customers c
                where c.organization_id=:organizationId and c.created_by_membership_id=:membershipId
                  and c.lead_review_status='VALID'
                  and c.archived_at is null
                  and c.created_at >= (:reportDate::date::timestamp at time zone 'Asia/Shanghai')
                  and c.created_at < ((:reportDate::date + 1)::timestamp at time zone 'Asia/Shanghai')
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("membershipId", membershipId)
                .addValue("reportDate", date), Long.class);
        return count == null ? 0 : count;
    }

    private DailyReportDtos.Response response(DailyReportEntity row, String displayName, long leadCount,
                                               boolean performanceManaged, int deepCount, int pendingDeepCount) {
        return new DailyReportDtos.Response(
                row.getId(), row.getMembershipId(), displayName, row.getReportDate(), leadCount,
                performanceManaged ? deepCount : row.getDeepCommunicationCount(), pendingDeepCount,
                performanceManaged, row.getTodayIssues(), row.getTomorrowPlan(),
                row.getSubmittedAt(), row.getUpdatedAt(), row.getVersion(), true);
    }

    private void validateDate(LocalDate date) {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        if (date.isBefore(reportProperties.startDate())) {
            throw ApiException.badRequest("OUTSIDE_REPORT_PERIOD", "汇报日期不在任务周期内");
        }
        if (date.isAfter(today)) throw ApiException.badRequest("FUTURE_REPORT_DATE", "不能提前提交未来日报");
    }

    private void requireContributor(CurrentActor actor) {
        if (actor.roles().stream().noneMatch(role -> role == RoleCode.ORG_ADMIN || role == RoleCode.TEAM_MANAGER || role == RoleCode.MEMBER)) {
            throw ApiException.forbidden("当前角色不能提交每日汇报");
        }
    }

    private String required(String value, String code, String message) {
        String normalized = TextNormalizer.optional(value);
        if (normalized == null) throw ApiException.badRequest(code, message);
        return normalized;
    }
}
