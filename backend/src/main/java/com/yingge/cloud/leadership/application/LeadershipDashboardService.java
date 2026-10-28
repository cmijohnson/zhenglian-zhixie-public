package com.yingge.cloud.leadership.application;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.domain.CustomerQuality;
import com.yingge.cloud.leadership.api.LeadershipDashboardDtos;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class LeadershipDashboardService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CurrentActorProvider actors;
    private final NamedParameterJdbcTemplate jdbc;
    private final LeadershipScreenSettingsService screenSettings;

    public LeadershipDashboardService(
            CurrentActorProvider actors,
            NamedParameterJdbcTemplate jdbc,
            LeadershipScreenSettingsService screenSettings) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.screenSettings = screenSettings;
    }

    @Transactional(readOnly = true)
    public LeadershipDashboardDtos.Overview overview(
            UUID requestedTeamId, UUID requestedMembershipId, Integer requestedDays) {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin() && !actor.isTeamManager()) {
            throw ApiException.forbidden("只有管理员和团队长可以查看领导数据大屏");
        }

        int days = Math.max(7, Math.min(requestedDays == null ? 30 : requestedDays, 90));
        LocalDate endDate = LocalDate.now(BUSINESS_ZONE);
        LocalDate startDate = endDate.minusDays(days - 1L);

        List<LeadershipDashboardDtos.TeamOption> teams = teamOptions(actor);
        Set<UUID> allowedTeamIds = teams.stream().map(LeadershipDashboardDtos.TeamOption::id)
                .collect(Collectors.toSet());
        if (requestedTeamId != null && !allowedTeamIds.contains(requestedTeamId)) {
            throw ApiException.forbidden("不能查看该团队的数据");
        }

        List<LeadershipDashboardDtos.MemberOption> members =
                memberOptions(actor, requestedTeamId, allowedTeamIds);
        if (requestedMembershipId != null && members.stream()
                .noneMatch(member -> member.membershipId().equals(requestedMembershipId))) {
            throw ApiException.forbidden("不能查看该成员的数据");
        }

        List<UUID> memberIds = requestedMembershipId == null
                ? members.stream().map(LeadershipDashboardDtos.MemberOption::membershipId).toList()
                : List.of(requestedMembershipId);
        var settings = screenSettings.effective(actor.organizationId());
        Scope scope = new Scope(actor, requestedTeamId, requestedMembershipId, allowedTeamIds,
                memberIds, startDate, endDate, settings.featuredGrade());

        LeadershipDashboardDtos.Headline headline = headline(scope);
        var stale = aClassStale(scope);
        var schoolComparison = schoolComparison(scope);
        var messages = displayMessages(scope);
        return new LeadershipDashboardDtos.Overview(
                Instant.now(), lastBusinessActivityAt(scope), startDate, endDate, days, actor.isAdmin(),
                requestedTeamId, requestedMembershipId, teams, members,
                headline, recentCustomerActivities(scope), rankings(scope), dailyConversions(scope),
                customerQuality(scope, headline.totalCustomers()),
                taskSummary(scope), taskProgress(scope), taskPublications(scope),
                sourceBreakdown(scope), messages, settings.featuredGrade(), stale, schoolComparison,
                tickerItems(stale, schoolComparison, messages), settings
        );
    }

    private Instant lastBusinessActivityAt(Scope scope) {
        MapSqlParameterSource params = customerParams(scope);
        String taskActivity = "";
        if (!scope.memberIds().isEmpty()) {
            taskActivity = """
                    union all
                    select max(assignment.updated_at)
                    from work_task_assignments assignment
                    where assignment.organization_id=:organizationId
                      and assignment.membership_id in (:memberIds)
                    union all
                    select max(task.updated_at)
                    from work_tasks task
                    where task.organization_id=:organizationId
                      and (
                        task.distribution_type='SELF_CLAIM'
                        or exists (
                          select 1 from work_task_assignments assignment
                          where assignment.task_id=task.id
                            and assignment.membership_id in (:memberIds)
                        )
                      )
                    """;
        }
        Timestamp latest = jdbc.queryForObject("""
                select max(activity_at)
                from (
                  select max(c.updated_at) activity_at
                  from customers c
                  where
                """ + customerWhere(scope) + "\n" + taskActivity + """
                ) dashboard_activity
                """, params, Timestamp.class);
        return latest == null ? null : latest.toInstant();
    }

    private List<LeadershipDashboardDtos.DisplayMessage> displayMessages(Scope scope) {
        return jdbc.query("""
                select id, title, content, priority, published_at
                from announcements
                where organization_id=:organizationId
                  and status='PUBLISHED'
                  and show_on_leadership_screen=true
                order by pinned desc,
                         case priority when 'URGENT' then 0 when 'IMPORTANT' then 1 else 2 end,
                         published_at desc
                limit 20
                """, baseParams(scope), (row, index) -> new LeadershipDashboardDtos.DisplayMessage(
                row.getObject("id", UUID.class), row.getString("title"), row.getString("content"),
                row.getString("priority"), row.getTimestamp("published_at").toInstant()));
    }

    private List<LeadershipDashboardDtos.TeamOption> teamOptions(CurrentActor actor) {
        String condition = actor.isAdmin() ? "" : "and team.id in (:teamIds)";
        MapSqlParameterSource params = new MapSqlParameterSource("organizationId", actor.organizationId());
        if (!actor.isAdmin()) {
            if (actor.managedTeamIds().isEmpty()) {
                throw ApiException.forbidden("当前账户尚未负责任何团队");
            }
            params.addValue("teamIds", actor.managedTeamIds());
        }
        return jdbc.query("""
                select team.id, team.name
                from teams team
                where team.organization_id=:organizationId and team.active=true
                """ + condition + " order by team.name", params,
                (row, index) -> new LeadershipDashboardDtos.TeamOption(
                        row.getObject("id", UUID.class), row.getString("name")));
    }

    private List<LeadershipDashboardDtos.MemberOption> memberOptions(
            CurrentActor actor, UUID selectedTeamId, Set<UUID> allowedTeamIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("organizationId", actor.organizationId());
        String scope;
        if (selectedTeamId != null) {
            scope = """
                    and exists (
                      select 1 from team_members filter_member
                      where filter_member.membership_id=membership.id
                        and filter_member.team_id=:selectedTeamId
                    )
                    """;
            params.addValue("selectedTeamId", selectedTeamId);
        } else if (!actor.isAdmin()) {
            scope = """
                    and exists (
                      select 1 from team_members filter_member
                      where filter_member.membership_id=membership.id
                        and filter_member.team_id in (:allowedTeamIds)
                    )
                    """;
            params.addValue("allowedTeamIds", allowedTeamIds);
        } else {
            scope = "";
        }
        return jdbc.query("""
                select membership.id, account.display_name, account.username,
                       coalesce(string_agg(distinct team.name, ' / ' order by team.name), '') team_names
                from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                left join team_members team_member on team_member.membership_id=membership.id
                left join teams team on team.id=team_member.team_id and team.active=true
                where membership.organization_id=:organizationId
                  and membership.status='ACTIVE'
                  and exists (
                    select 1 from membership_roles membership_role
                    join roles role on role.id=membership_role.role_id
                    where membership_role.membership_id=membership.id
                      and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER')
                  )
                """ + scope + """
                group by membership.id, account.display_name, account.username
                order by account.display_name, membership.id
                """, params, (row, index) -> new LeadershipDashboardDtos.MemberOption(
                row.getObject("id", UUID.class), row.getString("display_name"),
                row.getString("username"), row.getString("team_names")));
    }

    private LeadershipDashboardDtos.Headline headline(Scope scope) {
        MapSqlParameterSource params = customerParams(scope).addValue("today", scope.endDate());
        return jdbc.queryForObject("""
                select count(*) total_customers,
                       count(*) filter (where c.friend_added_at is not null) added_customers,
                       count(*) filter (where c.friend_trusted_at is not null) trusted_customers,
                       count(*) filter (where c.friend_qualified_at is not null) qualified_customers,
                       count(*) filter (
                         where c.created_at >= (cast(:today as date)::timestamp at time zone 'Asia/Shanghai')
                           and c.created_at < ((cast(:today as date) + 1)::timestamp at time zone 'Asia/Shanghai')
                       ) today_new_customers,
                       count(*) filter (
                         where c.friend_qualified_at >= (cast(:today as date)::timestamp at time zone 'Asia/Shanghai')
                           and c.friend_qualified_at < ((cast(:today as date) + 1)::timestamp at time zone 'Asia/Shanghai')
                       ) today_qualified_customers
                from customers c
                where
                """ + customerWhere(scope), params, (row, index) -> {
            long total = row.getLong("total_customers");
            long added = row.getLong("added_customers");
            long trusted = row.getLong("trusted_customers");
            long qualified = row.getLong("qualified_customers");
            return new LeadershipDashboardDtos.Headline(
                    total, added, trusted, qualified,
                    row.getLong("today_new_customers"), row.getLong("today_qualified_customers"),
                    rate(added, total), rate(trusted, added), rate(qualified, trusted)
            );
        });
    }

    private List<LeadershipDashboardDtos.CustomerActivity> recentCustomerActivities(Scope scope) {
        return jdbc.query("""
                select c.id, c.full_name, owner.display_name owner_name,
                       coalesce(team.name, '未分组') team_name,
                       c.current_status, c.intention_level, c.created_at, c.updated_at
                from customers c
                join memberships owner_membership on owner_membership.id=c.owner_membership_id
                join user_accounts owner on owner.id=owner_membership.user_id
                left join teams team on team.id=c.team_id and team.active=true
                where
                """ + customerWhere(scope) + "\n" + """
                order by c.updated_at desc, c.id
                limit 100
                """, customerParams(scope), (row, index) -> new LeadershipDashboardDtos.CustomerActivity(
                row.getObject("id", UUID.class), row.getString("full_name"),
                row.getString("owner_name"), row.getString("team_name"),
                row.getString("current_status"), row.getString("intention_level"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant()));
    }

    private List<LeadershipDashboardDtos.MemberRanking> rankings(Scope scope) {
        if (scope.memberIds().isEmpty()) return List.of();
        MapSqlParameterSource params = baseParams(scope)
                .addValue("rankingDate", scope.endDate())
                .addValue("qualityCategory", CustomerQuality.CATEGORY)
                .addValue("qualityALabel", CustomerQuality.LEVELS.getFirst().label())
                .addValue("featuredGrade", scope.featuredGrade())
                .addValue("featuredGradeShort", scope.featuredGrade().substring(2));
        return jdbc.query("""
                select membership.id membership_id, account.display_name,
                       coalesce(string_agg(distinct team.name, ' / ' order by team.name), '') team_names,
                       count(distinct customer.id) total_customers,
                       count(distinct customer.id) filter (where customer.friend_added_at is not null) added_customers,
                       count(distinct customer.id) filter (where customer.friend_trusted_at is not null) trusted_customers,
                       count(distinct customer.id) filter (where customer.friend_qualified_at is not null) qualified_customers,
                       count(distinct customer.id) filter (
                         where exists (
                           select 1
                           from customer_tags quality_customer_tag
                           join tags quality_tag on quality_tag.id=quality_customer_tag.tag_id
                           where quality_customer_tag.customer_id=customer.id
                             and quality_tag.organization_id=membership.organization_id
                             and quality_tag.active=true
                             and quality_tag.category=:qualityCategory
                             and quality_tag.name=:qualityALabel
                         )
                       ) quality_a_customers,
                       count(distinct customer.id) filter (
                         where customer.created_at >=
                                 (cast(:rankingDate as date)::timestamp at time zone 'Asia/Shanghai')
                           and customer.created_at <
                                 ((cast(:rankingDate as date) + 1)::timestamp at time zone 'Asia/Shanghai')
                       ) today_customers,
                       count(distinct assignment.id) assigned_tasks,
                       count(distinct assignment.id) filter (where assignment.status='COMPLETED') completed_tasks
                from memberships membership
                join user_accounts account on account.id=membership.user_id
                left join team_members team_member on team_member.membership_id=membership.id
                left join teams team on team.id=team_member.team_id and team.active=true
                left join customers customer on customer.owner_membership_id=membership.id
                    and customer.organization_id=membership.organization_id
                    and customer.archived_at is null
                    and regexp_replace(coalesce(customer.grade, ''), '[^0-9]', '', 'g')
                        in (:featuredGradeShort, :featuredGrade)
                left join work_task_assignments assignment on assignment.membership_id=membership.id
                    and assignment.organization_id=membership.organization_id
                where membership.organization_id=:organizationId
                  and membership.id in (:memberIds)
                group by membership.id, account.display_name
                order by qualified_customers desc, trusted_customers desc, added_customers desc,
                         total_customers desc, account.display_name
                """, params, (row, index) -> {
            long assigned = row.getLong("assigned_tasks");
            long completed = row.getLong("completed_tasks");
            return new LeadershipDashboardDtos.MemberRanking(
                    row.getObject("membership_id", UUID.class), row.getString("display_name"),
                    row.getString("team_names"), row.getLong("total_customers"),
                    row.getLong("added_customers"), row.getLong("trusted_customers"),
                    row.getLong("qualified_customers"), row.getLong("quality_a_customers"),
                    row.getLong("today_customers"),
                    assigned, completed, rate(completed, assigned)
            );
        });
    }

    private List<LeadershipDashboardDtos.DailyConversion> dailyConversions(Scope scope) {
        MapSqlParameterSource params = customerParams(scope)
                .addValue("startDate", scope.startDate()).addValue("endDate", scope.endDate());
        String where = customerWhere(scope);
        String sql = """
                with days as (
                  select generate_series(cast(:startDate as date), cast(:endDate as date),
                                         interval '1 day')::date as metric_date
                ),
                events as (
                  select (c.friend_added_at at time zone 'Asia/Shanghai')::date as metric_date,
                         count(*)::bigint added_count, 0::bigint trusted_count, 0::bigint qualified_count
                  from customers c
                  where %s
                    and c.friend_added_at >= (cast(:startDate as date)::timestamp at time zone 'Asia/Shanghai')
                    and c.friend_added_at < ((cast(:endDate as date) + 1)::timestamp at time zone 'Asia/Shanghai')
                  group by 1
                  union all
                  select (c.friend_trusted_at at time zone 'Asia/Shanghai')::date as metric_date,
                         0::bigint, count(*)::bigint, 0::bigint
                  from customers c
                  where %s
                    and c.friend_trusted_at >= (cast(:startDate as date)::timestamp at time zone 'Asia/Shanghai')
                    and c.friend_trusted_at < ((cast(:endDate as date) + 1)::timestamp at time zone 'Asia/Shanghai')
                  group by 1
                  union all
                  select (c.friend_qualified_at at time zone 'Asia/Shanghai')::date as metric_date,
                         0::bigint, 0::bigint, count(*)::bigint
                  from customers c
                  where %s
                    and c.friend_qualified_at >= (cast(:startDate as date)::timestamp at time zone 'Asia/Shanghai')
                    and c.friend_qualified_at < ((cast(:endDate as date) + 1)::timestamp at time zone 'Asia/Shanghai')
                  group by 1
                ),
                daily as (
                  select metric_date, sum(added_count) added_count, sum(trusted_count) trusted_count,
                         sum(qualified_count) qualified_count
                  from events group by metric_date
                ),
                prior as (
                  select count(*)::bigint prior_qualified
                  from customers c
                  where %s
                    and c.friend_qualified_at < (cast(:startDate as date)::timestamp at time zone 'Asia/Shanghai')
                )
                select days.metric_date,
                       coalesce(daily.added_count, 0) added_count,
                       coalesce(daily.trusted_count, 0) trusted_count,
                       coalesce(daily.qualified_count, 0) qualified_count,
                       prior.prior_qualified + sum(coalesce(daily.qualified_count, 0))
                         over (order by days.metric_date) cumulative_qualified
                from days
                cross join prior
                left join daily on daily.metric_date=days.metric_date
                order by days.metric_date
                """.formatted(where, where, where, where);
        return jdbc.query(sql, params, (row, index) -> new LeadershipDashboardDtos.DailyConversion(
                row.getObject("metric_date", LocalDate.class), row.getLong("added_count"),
                row.getLong("trusted_count"), row.getLong("qualified_count"),
                row.getLong("cumulative_qualified")));
    }

    private LeadershipDashboardDtos.TaskSummary taskSummary(Scope scope) {
        if (scope.memberIds().isEmpty()) {
            return new LeadershipDashboardDtos.TaskSummary(0, 0, 0, 0, 0, 0, 0, 0);
        }
        return jdbc.queryForObject("""
                select count(*) assignment_count,
                       count(*) filter (where assignment.status='PENDING') pending_count,
                       count(*) filter (where assignment.status='IN_PROGRESS') in_progress_count,
                       count(*) filter (where assignment.status='SUBMITTED') submitted_count,
                       count(*) filter (where assignment.status='COMPLETED') completed_count,
                       count(*) filter (where assignment.status='RETURNED') returned_count,
                       count(*) filter (
                         where task.due_at < now() and assignment.status <> 'COMPLETED'
                       ) overdue_count
                from work_task_assignments assignment
                join work_tasks task on task.id=assignment.task_id
                where assignment.organization_id=:organizationId
                  and assignment.membership_id in (:memberIds)
                """, baseParams(scope), (row, index) -> {
            long count = row.getLong("assignment_count");
            long completed = row.getLong("completed_count");
            return new LeadershipDashboardDtos.TaskSummary(
                    count, row.getLong("pending_count"), row.getLong("in_progress_count"),
                    row.getLong("submitted_count"), completed, row.getLong("returned_count"),
                    row.getLong("overdue_count"), rate(completed, count)
            );
        });
    }

    private List<LeadershipDashboardDtos.TaskProgress> taskProgress(Scope scope) {
        if (scope.memberIds().isEmpty()) return List.of();
        MapSqlParameterSource params = baseParams(scope).addValue("startDate", scope.startDate());
        return jdbc.query("""
                select task.id, task.title, task.due_at, task.status,
                       count(assignment.id) assignment_count,
                       count(assignment.id) filter (where assignment.status='COMPLETED') completed_count,
                       count(assignment.id) filter (where assignment.status='SUBMITTED') submitted_count
                from work_tasks task
                join work_task_assignments assignment on assignment.task_id=task.id
                  and assignment.membership_id in (:memberIds)
                where task.organization_id=:organizationId
                  and (task.status='PUBLISHED'
                       or task.due_at >= (cast(:startDate as date)::timestamp at time zone 'Asia/Shanghai'))
                group by task.id, task.title, task.due_at, task.status, task.created_at
                order by case when task.status='PUBLISHED' then 0 else 1 end,
                         task.due_at, task.created_at desc
                limit 8
                """, params, (row, index) -> {
            long assigned = row.getLong("assignment_count");
            long completed = row.getLong("completed_count");
            Instant dueAt = row.getTimestamp("due_at").toInstant();
            return new LeadershipDashboardDtos.TaskProgress(
                    row.getObject("id", UUID.class), row.getString("title"), dueAt,
                    row.getString("status"), assigned, completed, row.getLong("submitted_count"),
                    rate(completed, assigned), dueAt.isBefore(Instant.now()) && completed < assigned
            );
        });
    }

    private List<LeadershipDashboardDtos.TaskPublication> taskPublications(Scope scope) {
        if (scope.memberIds().isEmpty()) return List.of();
        return jdbc.query("""
                select task.id, task.title, task.distribution_type, task.start_at, task.due_at,
                       task.created_at, publisher.display_name publisher_name,
                       count(assignment.id) assignment_count
                from work_tasks task
                join memberships creator on creator.id=task.created_by_membership_id
                join user_accounts publisher on publisher.id=creator.user_id
                left join work_task_assignments assignment on assignment.task_id=task.id
                  and assignment.membership_id in (:memberIds)
                where task.organization_id=:organizationId
                  and task.status='PUBLISHED'
                  and (
                    task.distribution_type='SELF_CLAIM'
                    or exists (
                      select 1 from work_task_assignments scoped_assignment
                      where scoped_assignment.task_id=task.id
                        and scoped_assignment.membership_id in (:memberIds)
                    )
                  )
                group by task.id, task.title, task.distribution_type, task.start_at, task.due_at,
                         task.created_at, publisher.display_name
                order by task.created_at desc, task.id
                limit 12
                """, baseParams(scope), (row, index) -> new LeadershipDashboardDtos.TaskPublication(
                row.getObject("id", UUID.class), row.getString("title"),
                row.getString("distribution_type"), row.getTimestamp("start_at").toInstant(),
                row.getTimestamp("due_at").toInstant(), row.getTimestamp("created_at").toInstant(),
                row.getString("publisher_name"), row.getLong("assignment_count")));
    }

    private List<LeadershipDashboardDtos.SourceSlice> sourceBreakdown(Scope scope) {
        MapSqlParameterSource params = customerParams(scope);
        List<SourceCount> rows = jdbc.query("""
                select grouped.source, grouped.customer_count, grouped.added_count,
                       grouped.trusted_count, grouped.qualified_count,
                       sum(grouped.customer_count) over () total_count
                from (
                  select coalesce(dictionary.label, nullif(btrim(c.source_channel_legacy), ''), '未填写来源') source,
                         count(*) customer_count,
                         count(*) filter (where c.friend_added_at is not null) added_count,
                         count(*) filter (where c.friend_trusted_at is not null) trusted_count,
                         count(*) filter (where c.friend_qualified_at is not null) qualified_count
                  from customers c
                  left join dictionary_items dictionary on dictionary.id=c.source_dictionary_id
                  where
                  """ + customerWhere(scope) + """
                  group by 1
                ) grouped
                order by grouped.customer_count desc, grouped.source
                """, params, (row, index) -> new SourceCount(
                row.getString("source"), row.getLong("customer_count"), row.getLong("added_count"),
                row.getLong("trusted_count"), row.getLong("qualified_count"), row.getLong("total_count")));
        long total = rows.isEmpty() ? 0 : rows.getFirst().total();
        return rows.stream().map(row -> new LeadershipDashboardDtos.SourceSlice(
                row.source(), row.count(), row.added(), row.trusted(), row.qualified(),
                rate(row.count(), total), rate(row.added(), row.count()),
                rate(row.trusted(), row.added()), rate(row.qualified(), row.trusted()))).toList();
    }

    private LeadershipDashboardDtos.CustomerQualitySummary customerQuality(Scope scope, long totalCustomers) {
        Map<String, Long> counts = jdbc.query("""
                select tag.name, count(distinct c.id) customer_count
                from customers c
                join customer_tags customer_tag on customer_tag.customer_id=c.id
                join tags tag on tag.id=customer_tag.tag_id
                  and tag.organization_id=c.organization_id
                  and tag.active=true
                  and tag.category=:qualityCategory
                where
                """ + customerWhere(scope) + "\n" + """
                group by tag.name
                """, customerParams(scope).addValue("qualityCategory", CustomerQuality.CATEGORY),
                resultSet -> {
                    Map<String, Long> result = new java.util.LinkedHashMap<>();
                    while (resultSet.next()) {
                        result.put(resultSet.getString("name"), resultSet.getLong("customer_count"));
                    }
                    return result;
                });
        List<LeadershipDashboardDtos.CustomerQualitySlice> levels = CustomerQuality.LEVELS.stream()
                .map(level -> {
                    long count = counts.getOrDefault(level.label(), 0L);
                    return new LeadershipDashboardDtos.CustomerQualitySlice(
                            level.code(), level.label(), level.description(), level.color(),
                            count, rate(count, totalCustomers));
                }).toList();
        long classified = levels.stream().mapToLong(LeadershipDashboardDtos.CustomerQualitySlice::customerCount).sum();
        return new LeadershipDashboardDtos.CustomerQualitySummary(
                classified, Math.max(0, totalCustomers - classified), levels);
    }

    private LeadershipDashboardDtos.AClassStaleSummary aClassStale(Scope scope) {
        FollowUpPolicy policy = jdbc.query("""
                select a_class_stale_enabled, stale_after_hours
                from organization_follow_up_policies
                where organization_id=:organizationId
                """, baseParams(scope), (row, index) -> new FollowUpPolicy(
                row.getBoolean("a_class_stale_enabled"), row.getInt("stale_after_hours")))
                .stream().findFirst().orElse(new FollowUpPolicy(true, 72));
        Instant calculatedAt = Instant.now();
        if (!policy.enabled()) {
            return new LeadershipDashboardDtos.AClassStaleSummary(
                    false, policy.thresholdHours(), 0, 0, List.of(), calculatedAt);
        }

        MapSqlParameterSource params = customerParams(scope)
                .addValue("qualityCategory", CustomerQuality.CATEGORY)
                .addValue("qualityALabel", CustomerQuality.LEVELS.getFirst().label())
                .addValue("staleHours", policy.thresholdHours());
        List<StaleOwnerCount> counts = jdbc.query("""
                select case
                         when owner_membership.status='ACTIVE' and owner_account.enabled=true
                         then c.owner_membership_id else null
                       end membership_id,
                       case
                         when owner_membership.status='ACTIVE' and owner_account.enabled=true
                         then owner_account.display_name else '未分配/已停用'
                       end display_name,
                       count(distinct c.id) customer_count
                from customers c
                left join memberships owner_membership on owner_membership.id=c.owner_membership_id
                left join user_accounts owner_account on owner_account.id=owner_membership.user_id
                where
                """ + customerWhere(scope) + """

                  and coalesce(c.last_contact_at, c.created_at)
                      < now() - make_interval(hours => :staleHours)
                  and exists (
                    select 1
                    from customer_tags stale_customer_tag
                    join tags stale_tag on stale_tag.id=stale_customer_tag.tag_id
                    where stale_customer_tag.customer_id=c.id
                      and stale_tag.organization_id=c.organization_id
                      and stale_tag.active=true
                      and stale_tag.category=:qualityCategory
                      and stale_tag.name=:qualityALabel
                  )
                group by 1, 2
                order by customer_count desc, display_name
                """, params, (row, index) -> new StaleOwnerCount(
                row.getObject("membership_id", UUID.class), row.getString("display_name"),
                row.getLong("customer_count")));
        long total = counts.stream().mapToLong(StaleOwnerCount::customerCount).sum();
        long unassigned = counts.stream().filter(item -> item.membershipId() == null)
                .mapToLong(StaleOwnerCount::customerCount).sum();
        List<LeadershipDashboardDtos.StaleOwner> owners = counts.stream()
                .filter(item -> item.membershipId() != null)
                .map(item -> new LeadershipDashboardDtos.StaleOwner(
                        item.membershipId(), item.displayName(), item.customerCount()))
                .toList();
        return new LeadershipDashboardDtos.AClassStaleSummary(
                true, policy.thresholdHours(), total, unassigned, owners, calculatedAt);
    }

    private LeadershipDashboardDtos.SchoolComparison schoolComparison(Scope scope) {
        MapSqlParameterSource params = customerParams(scope)
                .addValue("featuredGrade", scope.featuredGrade())
                .addValue("featuredGradeShort", scope.featuredGrade().substring(2));
        long[] values = jdbc.queryForObject("""
                select count(*) filter (where btrim(c.school)='示例大学') jsu_count,
                       count(*) filter (where btrim(c.school)='示例科技大学') jsust_count
                from customers c
                where
                """ + customerWhere(scope) + """

                  and regexp_replace(coalesce(c.grade, ''), '[^0-9]', '', 'g')
                      in (:featuredGradeShort, :featuredGrade)
                """, params, (row, index) -> new long[]{
                row.getLong("jsu_count"), row.getLong("jsust_count")});
        long jsu = values == null ? 0 : values[0];
        long jsust = values == null ? 0 : values[1];
        String leader = jsu == jsust ? "TIED" : (jsu > jsust ? "JIANGSU_UNIVERSITY" : "JIANGSU_UST");
        return new LeadershipDashboardDtos.SchoolComparison(
                scope.featuredGrade(), jsu, jsust, Math.abs(jsu - jsust), leader);
    }

    private List<LeadershipDashboardDtos.TickerItem> tickerItems(
            LeadershipDashboardDtos.AClassStaleSummary stale,
            LeadershipDashboardDtos.SchoolComparison school,
            List<LeadershipDashboardDtos.DisplayMessage> messages) {
        List<LeadershipDashboardDtos.TickerItem> items = new ArrayList<>();
        if (stale.enabled()) {
            String ownerText = stale.owners().isEmpty() ? "暂无在职归属人"
                    : stale.owners().stream().limit(5)
                    .map(owner -> owner.displayName() + " " + owner.customerCount() + " 人")
                    .collect(Collectors.joining("、"));
            String threshold = stale.thresholdHours() % 24 == 0
                    ? stale.thresholdHours() / 24 + " 天" : stale.thresholdHours() + " 小时";
            items.add(new LeadershipDashboardDtos.TickerItem(
                    "a-class-stale", "FOLLOW_UP_RISK",
                    stale.customerCount() > 0 ? "URGENT" : "NORMAL",
                    "A 类客户超过 " + threshold + " 未有效更新："
                            + stale.customerCount() + " 人；归属：" + ownerText
                            + (stale.unassignedCount() > 0 ? "；未分配/已停用 " + stale.unassignedCount() + " 人" : "")));
        } else {
            items.add(new LeadershipDashboardDtos.TickerItem(
                    "a-class-stale-disabled", "FOLLOW_UP_RISK", "NORMAL",
                    "A 类客户超时跟进统计已由管理员关闭"));
        }
        String comparison = school.leader().equals("TIED") ? "两校数量持平"
                : (school.leader().equals("JIANGSU_UNIVERSITY") ? "示例大学领先 " : "示例科技大学领先 ")
                + school.difference() + " 人";
        items.add(new LeadershipDashboardDtos.TickerItem(
                "featured-school-comparison", "SCHOOL_COMPARISON", "IMPORTANT",
                school.featuredGrade() + " 级客户：示例大学 " + school.jiangsuUniversity()
                        + " 人，示例科技大学 " + school.jiangsuUniversityOfScienceAndTechnology()
                        + " 人，" + comparison));
        messages.forEach(message -> items.add(new LeadershipDashboardDtos.TickerItem(
                "announcement-" + message.id(), "ANNOUNCEMENT", message.priority(),
                message.title() + "：" + message.content())));
        return List.copyOf(items);
    }

    private String customerWhere(Scope scope) {
        List<String> conditions = new ArrayList<>();
        conditions.add("c.organization_id=:organizationId");
        conditions.add("c.archived_at is null");
        if (scope.selectedTeamId() != null) {
            conditions.add("""
                    (c.team_id=:selectedTeamId or exists (
                      select 1 from team_members owner_team
                      where owner_team.membership_id=c.owner_membership_id
                        and owner_team.team_id=:selectedTeamId
                    ))
                    """);
        } else if (!scope.actor().isAdmin()) {
            conditions.add("""
                    (c.team_id in (:allowedTeamIds) or exists (
                      select 1 from team_members owner_team
                      where owner_team.membership_id=c.owner_membership_id
                        and owner_team.team_id in (:allowedTeamIds)
                    ))
                    """);
        }
        if (scope.selectedMembershipId() != null) {
            conditions.add("c.owner_membership_id=:selectedMembershipId");
        }
        return String.join(" and ", conditions);
    }

    private MapSqlParameterSource baseParams(Scope scope) {
        return new MapSqlParameterSource("organizationId", scope.actor().organizationId())
                .addValue("memberIds", scope.memberIds())
                .addValue("allowedTeamIds", scope.allowedTeamIds())
                .addValue("selectedTeamId", scope.selectedTeamId())
                .addValue("selectedMembershipId", scope.selectedMembershipId());
    }

    private MapSqlParameterSource customerParams(Scope scope) {
        return baseParams(scope);
    }

    private double rate(long numerator, long denominator) {
        if (denominator <= 0) return 0;
        return BigDecimal.valueOf((double) numerator / denominator)
                .setScale(4, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private record Scope(
            CurrentActor actor,
            UUID selectedTeamId,
            UUID selectedMembershipId,
            Set<UUID> allowedTeamIds,
            List<UUID> memberIds,
            LocalDate startDate,
            LocalDate endDate,
            String featuredGrade
    ) {}

    private record FollowUpPolicy(boolean enabled, int thresholdHours) {}

    private record StaleOwnerCount(UUID membershipId, String displayName, long customerCount) {}

    private record SourceCount(
            String source,
            long count,
            long added,
            long trusted,
            long qualified,
            long total
    ) {}
}
