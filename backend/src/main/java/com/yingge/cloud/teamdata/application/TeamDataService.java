package com.yingge.cloud.teamdata.application;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.content.application.ContentCampaignProperties;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.teamdata.api.TeamDataDtos;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class TeamDataService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final CurrentActorProvider actors;
    private final NamedParameterJdbcTemplate jdbc;
    private final ContentCampaignProperties campaign;

    public TeamDataService(CurrentActorProvider actors, NamedParameterJdbcTemplate jdbc,
                           ContentCampaignProperties campaign) {
        this.actors = actors;
        this.jdbc = jdbc;
        this.campaign = campaign;
    }

    @Transactional(readOnly = true)
    public TeamDataDtos.Overview overview(UUID requestedTeamId, UUID requestedMembershipId,
                                          String requestedSearch, String requestedGrade) {
        CurrentActor actor = actors.require();
        if (!actor.isAdmin() && !actor.isTeamManager()) {
            throw ApiException.forbidden("只有管理员和团队长可以查看团队数据");
        }

        List<TeamDataDtos.TeamOption> teamOptions = teamOptions(actor);
        Set<UUID> allowedTeamIds = teamOptions.stream().map(TeamDataDtos.TeamOption::id)
                .collect(java.util.stream.Collectors.toSet());
        if (requestedTeamId != null && !allowedTeamIds.contains(requestedTeamId)) {
            throw ApiException.forbidden("不能查看该团队的数据");
        }

        List<TeamDataDtos.MemberOption> memberOptions = memberOptions(
                actor, requestedTeamId, allowedTeamIds);
        if (requestedMembershipId != null && memberOptions.stream()
                .noneMatch(member -> member.membershipId().equals(requestedMembershipId))) {
            throw ApiException.forbidden("不能查看该成员的数据");
        }

        List<UUID> memberIds = requestedMembershipId == null
                ? memberOptions.stream().map(TeamDataDtos.MemberOption::membershipId).toList()
                : List.of(requestedMembershipId);
        Scope scope = new Scope(actor, requestedTeamId, requestedMembershipId, allowedTeamIds, memberIds,
                normalizeFilter(requestedSearch, 200, "客户搜索"), normalizeFilter(requestedGrade, 20, "客户年级"));
        return new TeamDataDtos.Overview(
                teamOptions, memberOptions, stats(scope), reports(scope), contentProgress(scope), leadReviewQueue(scope),
                requestedTeamId, requestedMembershipId, actor.isAdmin());
    }

    private List<TeamDataDtos.TeamOption> teamOptions(CurrentActor actor) {
        String condition = actor.isAdmin() ? "" : "and t.id in (:teamIds)";
        MapSqlParameterSource params = new MapSqlParameterSource("organizationId", actor.organizationId());
        if (!actor.isAdmin()) {
            if (actor.managedTeamIds().isEmpty()) throw ApiException.forbidden("当前账户尚未负责任何团队");
            params.addValue("teamIds", actor.managedTeamIds());
        }
        return jdbc.query("""
                select t.id, t.name from teams t
                where t.organization_id=:organizationId and t.active=true
                """ + condition + " order by t.name", params,
                (row, index) -> new TeamDataDtos.TeamOption(
                        row.getObject("id", UUID.class), row.getString("name")));
    }

    private List<TeamDataDtos.MemberOption> memberOptions(CurrentActor actor, UUID requestedTeamId,
                                                           Set<UUID> allowedTeamIds) {
        MapSqlParameterSource params = new MapSqlParameterSource("organizationId", actor.organizationId());
        String scope;
        if (requestedTeamId != null) {
            scope = "and exists (select 1 from team_members filter_tm where filter_tm.membership_id=m.id and filter_tm.team_id=:teamId)";
            params.addValue("teamId", requestedTeamId);
        } else if (!actor.isAdmin()) {
            scope = "and exists (select 1 from team_members filter_tm where filter_tm.membership_id=m.id and filter_tm.team_id in (:teamIds))";
            params.addValue("teamIds", allowedTeamIds);
        } else {
            scope = "";
        }
        return jdbc.query("""
                select m.id, u.display_name, u.username,
                       coalesce(string_agg(distinct t.name, ' / ' order by t.name), '') team_names
                from memberships m
                join user_accounts u on u.id=m.user_id
                left join team_members tm on tm.membership_id=m.id
                left join teams t on t.id=tm.team_id and t.active=true
                where m.organization_id=:organizationId and m.status='ACTIVE'
                  and exists (select 1 from membership_roles mr join roles r on r.id=mr.role_id
                              where mr.membership_id=m.id and r.code in ('MEMBER','TEAM_MANAGER'))
                """ + scope + " group by m.id, u.display_name, u.username order by u.display_name",
                params, (row, index) -> new TeamDataDtos.MemberOption(
                        row.getObject("id", UUID.class), row.getString("display_name"),
                        row.getString("username"), row.getString("team_names")));
    }

    private TeamDataDtos.Stats stats(Scope scope) {
        if (scope.memberIds().isEmpty()) return new TeamDataDtos.Stats(0, 0, 0, 0, 0, 0, 0, 0);
        MapSqlParameterSource params = baseParams(scope).addValue("today", LocalDate.now(BUSINESS_ZONE))
                .addValue("campaignStart", campaign.startDate()).addValue("campaignEnd", campaign.endDate());
        long customers = value("select count(*) from customers c where " + filteredCustomerWhere(scope) +
                " and c.archived_at is null", params);
        long newCustomers = value("select count(*) from customers c where " + filteredCustomerWhere(scope) + """
                 and c.archived_at is null
                 and c.created_at >= (:today::date::timestamp at time zone 'Asia/Shanghai')
                 and c.created_at < ((:today::date + 1)::timestamp at time zone 'Asia/Shanghai')
                """, params);
        long grade26 = value("select count(*) from customers c where " + filteredCustomerWhere(scope) + """
                 and c.archived_at is null and c.lead_review_status='VALID'
                 and c.created_at >= (:today::date::timestamp at time zone 'Asia/Shanghai')
                 and c.created_at < ((:today::date + 1)::timestamp at time zone 'Asia/Shanghai')
                """, params);
        long pendingReviews = value("select count(*) from customers c where " + filteredCustomerWhere(scope) +
                " and c.archived_at is null and c.lead_review_status='PENDING'", params);
        long reportCount = value("""
                select count(*) from daily_reports dr
                where dr.organization_id=:organizationId and dr.membership_id in (:memberIds)
                  and dr.report_date=:today
                """, params);
        long xhs = contentCount("XIAOHONGSHU", params);
        long douyin = contentCount("DOUYIN", params);
        return new TeamDataDtos.Stats(scope.memberIds().size(), customers, newCustomers, grade26,
                pendingReviews, reportCount, xhs, douyin);
    }

    private List<TeamDataDtos.ReportItem> reports(Scope scope) {
        if (scope.memberIds().isEmpty()) return List.of();
        MapSqlParameterSource params = baseParams(scope);
        return jdbc.query("""
                select dr.id, dr.membership_id, u.display_name, dr.report_date,
                       dr.deep_communication_count, dr.today_issues, dr.tomorrow_plan, dr.updated_at,
                       (select count(*) from customers c where c.organization_id=dr.organization_id
                          and c.created_by_membership_id=dr.membership_id
                          and c.lead_review_status='VALID' and c.archived_at is null
                          and c.created_at >= (dr.report_date::timestamp at time zone 'Asia/Shanghai')
                          and c.created_at < ((dr.report_date + 1)::timestamp at time zone 'Asia/Shanghai')) grade26_count
                from daily_reports dr
                join memberships m on m.id=dr.membership_id
                join user_accounts u on u.id=m.user_id
                where dr.organization_id=:organizationId and dr.membership_id in (:memberIds)
                order by dr.report_date desc, u.display_name limit 100
                """, params, (row, index) -> new TeamDataDtos.ReportItem(
                row.getObject("id", UUID.class), row.getObject("membership_id", UUID.class),
                row.getString("display_name"), row.getObject("report_date", LocalDate.class),
                row.getLong("grade26_count"), row.getInt("deep_communication_count"),
                row.getString("today_issues"), row.getString("tomorrow_plan"),
                row.getTimestamp("updated_at").toInstant()));
    }

    private List<TeamDataDtos.ContentProgress> contentProgress(Scope scope) {
        if (scope.memberIds().isEmpty()) return List.of();
        MapSqlParameterSource params = baseParams(scope)
                .addValue("campaignStart", campaign.startDate()).addValue("campaignEnd", campaign.endDate());
        return jdbc.query("""
                select m.id, u.display_name,
                       count(cp.id) filter (where cp.platform='XIAOHONGSHU' and cp.review_status='VALID'
                           and cp.publish_date between :campaignStart and :campaignEnd) xhs_count,
                       count(cp.id) filter (where cp.platform='DOUYIN' and cp.review_status='VALID'
                           and cp.publish_date between :campaignStart and :campaignEnd) douyin_count
                from memberships m join user_accounts u on u.id=m.user_id
                left join content_posts cp on cp.membership_id=m.id and cp.organization_id=m.organization_id
                where m.organization_id=:organizationId and m.id in (:memberIds)
                group by m.id, u.display_name order by u.display_name
                """, params, (row, index) -> new TeamDataDtos.ContentProgress(
                row.getObject("id", UUID.class), row.getString("display_name"),
                row.getLong("xhs_count"), row.getLong("douyin_count")));
    }

    private List<TeamDataDtos.LeadReviewItem> leadReviewQueue(Scope scope) {
        if (scope.memberIds().isEmpty()) return List.of();
        return jdbc.query("""
                select c.id, c.full_name, c.owner_membership_id, owner_user.display_name owner_name,
                       c.created_by_membership_id, creator_user.display_name creator_name,
                       c.team_id, c.lead_review_status, c.lead_review_reason,
                       c.created_at, c.lead_reviewed_at
                from customers c
                join memberships owner_member on owner_member.id=c.owner_membership_id
                join user_accounts owner_user on owner_user.id=owner_member.user_id
                join memberships creator_member on creator_member.id=c.created_by_membership_id
                join user_accounts creator_user on creator_user.id=creator_member.user_id
                where
                """ + filteredCustomerWhere(scope) + """
                  and c.archived_at is null and c.lead_review_status='PENDING'
                order by c.created_at asc limit 200
                """, baseParams(scope), (row, index) -> new TeamDataDtos.LeadReviewItem(
                row.getObject("id", UUID.class), row.getString("full_name"),
                row.getObject("owner_membership_id", UUID.class), row.getString("owner_name"),
                row.getObject("created_by_membership_id", UUID.class), row.getString("creator_name"),
                row.getObject("team_id", UUID.class), row.getString("lead_review_status"),
                row.getString("lead_review_reason"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("lead_reviewed_at") == null ? null : row.getTimestamp("lead_reviewed_at").toInstant()));
    }

    private long contentCount(String platform, MapSqlParameterSource params) {
        params.addValue("platform", platform);
        return value("""
                select count(*) from content_posts cp
                where cp.organization_id=:organizationId and cp.membership_id in (:memberIds)
                  and cp.review_status='VALID' and cp.platform=:platform
                  and cp.publish_date between :campaignStart and :campaignEnd
                """, params);
    }

    private long value(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private String customerScopeWhere(Scope scope) {
        List<String> conditions = new ArrayList<>();
        conditions.add("c.organization_id=:organizationId");
        if (scope.selectedTeamId() != null) {
            conditions.add("(c.team_id=:selectedTeamId or exists (select 1 from team_members owner_tm where owner_tm.membership_id=c.owner_membership_id and owner_tm.team_id=:selectedTeamId))");
        } else if (!scope.actor().isAdmin()) {
            conditions.add("(c.team_id in (:allowedTeamIds) or exists (select 1 from team_members owner_tm where owner_tm.membership_id=c.owner_membership_id and owner_tm.team_id in (:allowedTeamIds)))");
        }
        if (scope.selectedMembershipId() != null) {
            conditions.add("(c.owner_membership_id=:selectedMembershipId or c.created_by_membership_id=:selectedMembershipId)");
        }
        return String.join(" and ", conditions);
    }

    private String filteredCustomerWhere(Scope scope) {
        List<String> conditions = new ArrayList<>();
        conditions.add(customerScopeWhere(scope));
        if (scope.search() != null) {
            conditions.add("""
                    (lower(coalesce(c.full_name, '')) like :customerSearch
                     or lower(coalesce(c.school, '')) like :customerSearch
                     or lower(coalesce(c.phone, '')) like :customerSearch
                     or lower(coalesce(c.wechat_id, '')) like :customerSearch
                     or lower(coalesce(c.qq_id, '')) like :customerSearch)
                    """);
        }
        if (scope.grade() != null) {
            conditions.add(scope.grade().matches("20\\d{2}")
                    ? "(lower(btrim(coalesce(c.grade, ''))) in (:gradeVariants) or lower(btrim(coalesce(c.grade, ''))) like :gradePattern)"
                    : "lower(btrim(coalesce(c.grade, ''))) = :grade");
        }
        return String.join(" and ", conditions);
    }

    private MapSqlParameterSource baseParams(Scope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource("organizationId", scope.actor().organizationId())
                .addValue("memberIds", scope.memberIds())
                .addValue("allowedTeamIds", scope.allowedTeamIds())
                .addValue("selectedTeamId", scope.selectedTeamId())
                .addValue("selectedMembershipId", scope.selectedMembershipId());
        if (scope.search() != null) {
            params.addValue("customerSearch", "%" + scope.search().toLowerCase(Locale.ROOT) + "%");
        }
        if (scope.grade() != null) {
            String normalized = scope.grade().toLowerCase(Locale.ROOT);
            params.addValue("grade", normalized);
            if (normalized.matches("20\\d{2}")) {
                String shortGrade = normalized.substring(2);
                params.addValue("gradeVariants", List.of(
                        normalized, normalized + "级", normalized + "届",
                        shortGrade, shortGrade + "级", shortGrade + "届"));
                params.addValue("gradePattern", "%" + normalized + "%");
            }
        }
        return params;
    }

    private String normalizeFilter(String value, int maximumLength, String label) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maximumLength) {
            throw ApiException.badRequest("INVALID_TEAM_DATA_FILTER", label + "长度超出限制");
        }
        return normalized;
    }

    private record Scope(CurrentActor actor, UUID selectedTeamId, UUID selectedMembershipId,
                         Set<UUID> allowedTeamIds, List<UUID> memberIds, String search, String grade) {}
}
