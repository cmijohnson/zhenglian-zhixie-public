package com.yingge.cloud.organization.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customerpool.application.CustomerPoolService;
import com.yingge.cloud.organization.infrastructure.MembershipEntity;
import com.yingge.cloud.organization.infrastructure.MembershipRepository;
import com.yingge.cloud.organization.infrastructure.TeamEntity;
import com.yingge.cloud.organization.infrastructure.TeamRepository;
import com.yingge.cloud.organization.infrastructure.UserAccountEntity;
import com.yingge.cloud.organization.infrastructure.UserAccountRepository;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.task.application.TaskService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class OrganizationAdminService {
    private static final UUID TEAM_MANAGER_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final UserAccountRepository users;
    private final MembershipRepository memberships;
    private final TeamRepository teams;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TaskService tasks;
    private final CustomerPoolService customerPools;

    public OrganizationAdminService(CurrentActorProvider actors, PermissionService permissions,
                                    UserAccountRepository users, MembershipRepository memberships,
                                    TeamRepository teams, PasswordEncoder passwordEncoder,
                                    JdbcTemplate jdbc, AuditService audit, TaskService tasks,
                                    CustomerPoolService customerPools) {
        this.actors = actors;
        this.permissions = permissions;
        this.users = users;
        this.memberships = memberships;
        this.teams = teams;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
        this.audit = audit;
        this.tasks = tasks;
        this.customerPools = customerPools;
    }

    @Transactional(readOnly = true)
    public List<MemberResponse> members() {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        return jdbc.query("""
                select m.id, u.id user_id, u.username, u.display_name, m.status,
                       coalesce(string_agg(distinct r.code, ','), '') roles,
                       coalesce(string_agg(distinct t.name, ','), '') teams,
                       coalesce(string_agg(distinct t.id::text, ','), '') team_ids,
                       (select count(*) from customers c where c.organization_id=m.organization_id
                         and c.owner_membership_id=m.id and c.archived_at is null) owned_customer_count
                from memberships m
                join user_accounts u on u.id=m.user_id
                left join membership_roles mr on mr.membership_id=m.id
                left join roles r on r.id=mr.role_id
                left join team_members tm on tm.membership_id=m.id
                left join teams t on t.id=tm.team_id and t.active=true
                where m.organization_id=?
                group by m.id, u.id, u.username, u.display_name, m.status
                order by u.display_name
                """, (row, index) -> new MemberResponse(
                row.getObject("id", UUID.class), row.getObject("user_id", UUID.class), row.getString("username"),
                row.getString("display_name"), row.getString("status"), split(row.getString("roles")),
                split(row.getString("teams")), splitUuids(row.getString("team_ids")), row.getLong("owned_customer_count")
        ), actor.organizationId());
    }

    @Transactional
    public MemberResponse createMember(CreateMemberRequest request) {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        if (request.initialPassword() == null || request.initialPassword().isBlank()) {
            throw ApiException.badRequest("PASSWORD_REQUIRED", "初始密码不能为空");
        }
        if (request.initialPassword().length() > 200) {
            throw ApiException.badRequest("PASSWORD_TOO_LONG", "初始密码不能超过 200 个字符");
        }
        RoleCode role;
        try {
            role = RoleCode.valueOf(request.role());
        } catch (Exception exception) {
            throw ApiException.badRequest("INVALID_ROLE", "角色无效");
        }
        Instant now = Instant.now();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        try {
            if (role == RoleCode.TEAM_MANAGER && request.teamId() == null) {
                throw ApiException.badRequest("TEAM_REQUIRED", "团队长必须加入一个团队");
            }
            if (role == RoleCode.TEAM_MANAGER && hasLeader(request.teamId())) {
                throw ApiException.conflict("TEAM_LEADER_EXISTS", "该团队已经指定团队长，请在团队设置中更换");
            }
            users.saveAndFlush(new UserAccountEntity(userId, request.username().trim(),
                    passwordEncoder.encode(request.initialPassword()), request.displayName().trim(), now));
            memberships.saveAndFlush(new MembershipEntity(membershipId, actor.organizationId(), userId, now));
            UUID roleId = jdbc.queryForObject("select id from roles where organization_id is null and code=?", UUID.class, role.name());
            jdbc.update("insert into membership_roles (membership_id, role_id) values (?, ?)", membershipId, roleId);
            if (request.teamId() != null) {
                teams.findByIdAndOrganizationId(request.teamId(), actor.organizationId())
                        .orElseThrow(() -> ApiException.badRequest("TEAM_NOT_FOUND", "团队不存在"));
                jdbc.update("insert into team_members (team_id, membership_id, is_manager) values (?, ?, ?)",
                        request.teamId(), membershipId, role == RoleCode.TEAM_MANAGER);
            }
            if (role == RoleCode.MEMBER || role == RoleCode.TEAM_MANAGER) {
                tasks.assignActiveMandatoryTasks(actor.organizationId(), membershipId);
            }
        } catch (DataIntegrityViolationException exception) {
            if (String.valueOf(exception.getMostSpecificCause().getMessage())
                    .contains("team_members_single_leader_idx")) {
                throw ApiException.conflict("TEAM_LEADER_EXISTS", "该团队已经指定团队长，请在团队设置中更换");
            }
            throw ApiException.conflict("USERNAME_EXISTS", "用户名已经存在");
        }
        audit.record(actor, "MEMBER_CREATED", "MEMBERSHIP", membershipId.toString(), "SUCCESS",
                java.util.Map.of("role", role.name()));
        return members().stream().filter(member -> member.membershipId().equals(membershipId)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> teams() {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        return jdbc.query("""
                select t.id, t.name, t.active, leader.membership_id leader_membership_id,
                       leader.display_name leader_display_name,
                       (select count(*) from team_members member where member.team_id=t.id) member_count
                from teams t
                left join (
                    select team_member.team_id, team_member.membership_id, users.display_name
                    from team_members team_member
                    join memberships membership on membership.id=team_member.membership_id
                    join user_accounts users on users.id=membership.user_id
                    where team_member.is_manager=true
                ) leader on leader.team_id=t.id
                where t.organization_id=? and t.active=true
                order by t.name
                """, (row, index) -> new TeamResponse(
                row.getObject("id", UUID.class), row.getString("name"), row.getBoolean("active"),
                row.getObject("leader_membership_id", UUID.class), row.getString("leader_display_name"),
                row.getInt("member_count")
        ), actor.organizationId());
    }

    @Transactional
    public TeamResponse createTeam(String name) {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        if (name == null || name.isBlank()) throw ApiException.badRequest("TEAM_NAME_REQUIRED", "团队名称不能为空");
        try {
            TeamEntity saved = teams.saveAndFlush(new TeamEntity(actor.organizationId(), name.trim()));
            audit.record(actor, "TEAM_CREATED", "TEAM", saved.getId().toString(), "SUCCESS", null);
            return new TeamResponse(saved.getId(), saved.getName(), saved.isActive(), null, null, 0);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("TEAM_NAME_EXISTS", "同名团队已存在");
        }
    }

    @Transactional
    public MemberResponse updateMember(UUID membershipId, UpdateMemberRequest request) {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        MembershipEntity membership = requireMembership(membershipId, actor.organizationId());
        String displayName = request.displayName() == null ? "" : request.displayName().trim();
        if (displayName.isBlank()) throw ApiException.badRequest("DISPLAY_NAME_REQUIRED", "真实姓名不能为空");
        if (displayName.length() > 120) throw ApiException.badRequest("DISPLAY_NAME_TOO_LONG", "真实姓名不能超过 120 个字符");
        RoleCode role = parseRole(request.role());
        String status = request.status() == null ? "" : request.status().trim().toUpperCase();
        if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
            throw ApiException.badRequest("INVALID_MEMBER_STATUS", "成员状态无效");
        }
        if (membershipId.equals(actor.membershipId()) && (!"ACTIVE".equals(status) || role != RoleCode.ORG_ADMIN)) {
            throw ApiException.conflict("CANNOT_DEMOTE_SELF", "当前管理员不能停用自己或移除自己的管理员角色");
        }
        if ((role != RoleCode.ORG_ADMIN || "DISABLED".equals(status)) && isLastActiveAdmin(membershipId, actor.organizationId())) {
            throw ApiException.conflict("LAST_ADMIN_REQUIRED", "组织必须至少保留一名启用的管理员");
        }
        UUID teamId = request.teamId();
        if (teamId != null) requireActiveTeam(teamId, actor.organizationId());
        if (role == RoleCode.TEAM_MANAGER && teamId == null) {
            throw ApiException.badRequest("TEAM_REQUIRED", "团队长必须加入一个团队");
        }
        if (role == RoleCode.TEAM_MANAGER && hasOtherLeader(teamId, membershipId)) {
            throw ApiException.conflict("TEAM_LEADER_EXISTS", "该团队已有团队长，请先更换团队长");
        }

        List<UUID> previouslyLedTeams = jdbc.queryForList(
                "select team_id from team_members where membership_id=? and is_manager=true", UUID.class, membershipId);
        int releasedCustomerCount = 0;
        if ("DISABLED".equals(status)) {
            releasedCustomerCount = customerPools.releaseMemberCustomers(
                    actor.organizationId(), membershipId, actor.membershipId());
            jdbc.update("""
                    update reminders set status='CANCELLED', updated_at=now(), version=version+1
                    where organization_id=? and assigned_membership_id=? and status='PENDING'
                    """, actor.organizationId(), membershipId);
        }
        jdbc.update("update user_accounts set display_name=?, enabled=?, auth_version=auth_version+1, updated_at=now() where id=?",
                displayName, "ACTIVE".equals(status), membership.getUserId());
        jdbc.update("update memberships set status=?, updated_at=now() where id=?", status, membershipId);
        jdbc.update("delete from membership_roles where membership_id=?", membershipId);
        UUID roleId = jdbc.queryForObject("select id from roles where organization_id is null and code=?",
                UUID.class, role.name());
        jdbc.update("insert into membership_roles (membership_id, role_id) values (?, ?)", membershipId, roleId);
        jdbc.update("delete from team_members where membership_id=?", membershipId);
        if (teamId != null && "ACTIVE".equals(status)) {
            jdbc.update("insert into team_members (team_id, membership_id, is_manager) values (?, ?, ?)",
                    teamId, membershipId, role == RoleCode.TEAM_MANAGER);
        }
        previouslyLedTeams.forEach(ignored -> demoteIfNoLongerLeader(membershipId));
        expireSession(membershipId);
        audit.record(actor, "MEMBER_UPDATED", "MEMBERSHIP", membershipId.toString(), "SUCCESS",
                java.util.Map.of("role", role.name(), "status", status,
                        "teamId", teamId == null ? "" : teamId.toString(),
                        "releasedCustomerCount", releasedCustomerCount));
        return members().stream().filter(member -> member.membershipId().equals(membershipId)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteMember(UUID membershipId, UUID transferCustomerToMembershipId) {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        if (membershipId.equals(actor.membershipId())) {
            throw ApiException.conflict("CANNOT_DELETE_SELF", "不能删除当前登录的管理员账户");
        }
        MembershipEntity membership = requireMembership(membershipId, actor.organizationId());
        if (isLastActiveAdmin(membershipId, actor.organizationId())) {
            throw ApiException.conflict("LAST_ADMIN_REQUIRED", "组织必须至少保留一名启用的管理员");
        }
        int affectedCustomers = customerPools.releaseMemberCustomers(
                actor.organizationId(), membershipId, actor.membershipId());
        jdbc.update("""
                update reminders set status='CANCELLED', updated_at=now(), version=version+1
                where organization_id=? and assigned_membership_id=? and status='PENDING'
                """, actor.organizationId(), membershipId);
        jdbc.update("delete from customer_access_grants where organization_id=? and grantee_membership_id=?",
                actor.organizationId(), membershipId);
        jdbc.update("delete from team_members where membership_id=?", membershipId);
        jdbc.update("update memberships set status='DISABLED', updated_at=now() where id=?", membershipId);
        jdbc.update("update user_accounts set enabled=false, auth_version=auth_version+1, updated_at=now() where id=?", membership.getUserId());
        expireSession(membershipId);
        java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("releasedCustomerCount", affectedCustomers);
        details.put("legacyTransferTargetIgnored", transferCustomerToMembershipId == null
                ? "" : transferCustomerToMembershipId.toString());
        audit.record(actor, "MEMBER_DELETED", "MEMBERSHIP", membershipId.toString(), "SUCCESS", details);
    }

    @Transactional
    public void deleteTeam(UUID teamId) {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        TeamEntity team = requireActiveTeam(teamId, actor.organizationId());
        List<UUID> affectedMembers = jdbc.queryForList(
                "select membership_id from team_members where team_id=?", UUID.class, teamId);
        UUID formerLeader = jdbc.query("select membership_id from team_members where team_id=? and is_manager=true",
                (row, index) -> row.getObject("membership_id", UUID.class), teamId)
                .stream().findFirst().orElse(null);
        jdbc.update("update customers set team_id=null, updated_at=now(), version=version+1 where team_id=?", teamId);
        jdbc.update("delete from team_members where team_id=?", teamId);
        jdbc.update("update teams set active=false, updated_at=now() where id=?", teamId);
        if (formerLeader != null) demoteIfNoLongerLeader(formerLeader);
        affectedMembers.forEach(this::expireSession);
        audit.record(actor, "TEAM_DELETED", "TEAM", teamId.toString(), "SUCCESS",
                java.util.Map.of("name", team.getName(), "unlinkedMemberCount", affectedMembers.size()));
    }

    @Transactional
    public void assignTeam(UUID teamId, UUID membershipId, boolean manager) {
        if (manager) {
            assignLeader(teamId, membershipId);
            return;
        }
        var actor = actors.require();
        permissions.requireAdmin(actor);
        teams.findByIdAndOrganizationId(teamId, actor.organizationId())
                .orElseThrow(() -> ApiException.notFound("TEAM_NOT_FOUND", "团队不存在"));
        MembershipEntity membership = memberships.findById(membershipId)
                .filter(row -> row.getOrganizationId().equals(actor.organizationId()))
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在"));
        boolean currentLeader = jdbc.query(
                "select is_manager from team_members where team_id=? and membership_id=?",
                (row, index) -> row.getBoolean("is_manager"), teamId, membershipId)
                .stream().findFirst().orElse(false);
        if (currentLeader) {
            throw ApiException.conflict("TEAM_LEADER_CANNOT_REMOVE", "请先为团队指定新的团队长");
        }
        jdbc.update("""
                insert into team_members (team_id, membership_id, is_manager) values (?, ?, ?)
                on conflict (team_id, membership_id) do update set is_manager=excluded.is_manager
                """, teamId, membership.getId(), manager);
        audit.record(actor, "TEAM_MEMBER_ASSIGNED", "TEAM", teamId.toString(), "SUCCESS",
                java.util.Map.of("membershipId", membershipId.toString(), "manager", manager));
    }

    @Transactional
    public TeamResponse assignLeader(UUID teamId, UUID membershipId) {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        teams.findForUpdate(teamId, actor.organizationId())
                .orElseThrow(() -> ApiException.notFound("TEAM_NOT_FOUND", "团队不存在"));
        memberships.findById(membershipId)
                .filter(row -> row.getOrganizationId().equals(actor.organizationId()) && "ACTIVE".equals(row.getStatus()))
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在或已停用"));

        UUID previousLeader = jdbc.query("""
                select membership_id from team_members where team_id=? and is_manager=true
                """, (row, index) -> row.getObject("membership_id", UUID.class), teamId)
                .stream().findFirst().orElse(null);
        jdbc.update("update team_members set is_manager=false where team_id=? and is_manager=true", teamId);
        jdbc.update("""
                insert into team_members (team_id, membership_id, is_manager) values (?, ?, true)
                on conflict (team_id, membership_id) do update set is_manager=true
                """, teamId, membershipId);
        jdbc.update("""
                insert into membership_roles (membership_id, role_id) values (?, ?)
                on conflict (membership_id, role_id) do nothing
                """, membershipId, TEAM_MANAGER_ROLE_ID);
        if (previousLeader != null && !previousLeader.equals(membershipId)) {
            demoteIfNoLongerLeader(previousLeader);
        }
        expireSessions(previousLeader, membershipId);
        audit.record(actor, "TEAM_LEADER_ASSIGNED", "TEAM", teamId.toString(), "SUCCESS",
                java.util.Map.of("membershipId", membershipId.toString(), "previousMembershipId",
                        previousLeader == null ? "" : previousLeader.toString()));
        return teams().stream().filter(team -> team.id().equals(teamId)).findFirst().orElseThrow();
    }

    private void demoteIfNoLongerLeader(UUID membershipId) {
        boolean leadsAnotherTeam = Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from team_members where membership_id=? and is_manager=true)",
                Boolean.class, membershipId));
        if (leadsAnotherTeam) return;
        jdbc.update("delete from membership_roles where membership_id=? and role_id=?",
                membershipId, TEAM_MANAGER_ROLE_ID);
        Integer roleCount = jdbc.queryForObject(
                "select count(*) from membership_roles where membership_id=?", Integer.class, membershipId);
        if (roleCount != null && roleCount == 0) {
            jdbc.update("insert into membership_roles (membership_id, role_id) values (?, ?)",
                    membershipId, MEMBER_ROLE_ID);
        }
    }

    private void expireSessions(UUID previousLeader, UUID newLeader) {
        if (previousLeader != null && !previousLeader.equals(newLeader)) expireSession(previousLeader);
        expireSession(newLeader);
    }

    private void expireSession(UUID membershipId) {
        jdbc.update("""
                delete from spring_session where principal_name=(
                    select users.username from user_accounts users
                    join memberships membership on membership.user_id=users.id
                    where membership.id=?
                )
                """, membershipId);
    }

    private boolean hasLeader(UUID teamId) {
        return teamId != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from team_members where team_id=? and is_manager=true)",
                Boolean.class, teamId));
    }

    private boolean hasOtherLeader(UUID teamId, UUID membershipId) {
        return teamId != null && Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from team_members where team_id=? and is_manager=true and membership_id<>?)
                """, Boolean.class, teamId, membershipId));
    }

    private MembershipEntity requireMembership(UUID membershipId, UUID organizationId) {
        return memberships.findById(membershipId)
                .filter(row -> row.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> ApiException.notFound("MEMBER_NOT_FOUND", "成员不存在"));
    }

    private TeamEntity requireActiveTeam(UUID teamId, UUID organizationId) {
        return teams.findByIdAndOrganizationId(teamId, organizationId)
                .filter(TeamEntity::isActive)
                .orElseThrow(() -> ApiException.notFound("TEAM_NOT_FOUND", "团队不存在或已删除"));
    }

    private RoleCode parseRole(String value) {
        try {
            return RoleCode.valueOf(value == null ? "" : value.trim());
        } catch (Exception exception) {
            throw ApiException.badRequest("INVALID_ROLE", "角色无效");
        }
    }

    private boolean isLastActiveAdmin(UUID membershipId, UUID organizationId) {
        Boolean targetIsAdmin = jdbc.queryForObject("""
                select exists(select 1 from membership_roles mr join roles r on r.id=mr.role_id
                  where mr.membership_id=? and r.code='ORG_ADMIN')
                """, Boolean.class, membershipId);
        if (!Boolean.TRUE.equals(targetIsAdmin)) return false;
        Integer otherAdmins = jdbc.queryForObject("""
                select count(distinct m.id) from memberships m
                join membership_roles mr on mr.membership_id=m.id join roles r on r.id=mr.role_id
                where m.organization_id=? and m.status='ACTIVE' and r.code='ORG_ADMIN' and m.id<>?
                """, Integer.class, organizationId, membershipId);
        return otherAdmins == null || otherAdmins == 0;
    }

    private Set<String> split(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Set.of(value.split(","));
    }

    private Set<UUID> splitUuids(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(",")).map(UUID::fromString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public record CreateMemberRequest(String username, String displayName, String initialPassword, String role, UUID teamId) {}
    public record MemberResponse(UUID membershipId, UUID userId, String username, String displayName,
                                 String status, Set<String> roles, Set<String> teams,
                                 Set<UUID> teamIds, long ownedCustomerCount) {}
    public record UpdateMemberRequest(String displayName, String role, String status, UUID teamId) {}
    public record TeamResponse(UUID id, String name, boolean active, UUID leaderMembershipId,
                               String leaderDisplayName, int memberCount) {}
}
