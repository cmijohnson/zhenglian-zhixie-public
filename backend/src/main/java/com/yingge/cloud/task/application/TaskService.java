package com.yingge.cloud.task.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;
import com.yingge.cloud.task.api.TaskDtos;
import com.yingge.cloud.task.domain.TaskAssignmentSource;
import com.yingge.cloud.task.domain.TaskAssignmentStatus;
import com.yingge.cloud.task.domain.TaskDistributionType;
import com.yingge.cloud.task.domain.WorkTaskStatus;
import com.yingge.cloud.task.infrastructure.WorkTaskAssignmentEntity;
import com.yingge.cloud.task.infrastructure.WorkTaskAssignmentRepository;
import com.yingge.cloud.task.infrastructure.WorkTaskEntity;
import com.yingge.cloud.task.infrastructure.WorkTaskRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TaskService {
    private final WorkTaskRepository tasks;
    private final WorkTaskAssignmentRepository assignments;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public TaskService(WorkTaskRepository tasks, WorkTaskAssignmentRepository assignments,
                       CurrentActorProvider actors, PermissionService permissions,
                       JdbcTemplate jdbc, AuditService audit) {
        this.tasks = tasks;
        this.assignments = assignments;
        this.actors = actors;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TaskDtos.TaskResponse> list() {
        CurrentActor actor = actors.require();
        List<WorkTaskEntity> rows = tasks.findByOrganizationIdOrderByCreatedAtDesc(actor.organizationId());
        Map<UUID, WorkTaskAssignmentEntity> mine = assignments.findByMembershipIdAndTaskIdIn(
                        actor.membershipId(), rows.stream().map(WorkTaskEntity::getId).toList()).stream()
                .collect(Collectors.toMap(WorkTaskAssignmentEntity::getTaskId, Function.identity()));
        Map<UUID, String> names = memberNames(actor.organizationId());
        return rows.stream().map(row -> taskResponse(row, mine.get(row.getId()), names, actor)).toList();
    }

    @Transactional(readOnly = true)
    public List<TaskDtos.MatrixRow> matrix() {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        List<WorkTaskEntity> rows = tasks.findByOrganizationIdOrderByCreatedAtDesc(actor.organizationId());
        if (rows.isEmpty()) return List.of();

        List<UUID> taskIds = rows.stream().map(WorkTaskEntity::getId).toList();
        Map<UUID, String> names = memberNames(actor.organizationId());
        Map<UUID, WorkTaskAssignmentEntity> mine = assignments.findByMembershipIdAndTaskIdIn(
                        actor.membershipId(), taskIds).stream()
                .collect(Collectors.toMap(WorkTaskAssignmentEntity::getTaskId, Function.identity()));
        Map<UUID, List<WorkTaskAssignmentEntity>> byTask = assignments
                .findByTaskIdInOrderByUpdatedAtDesc(taskIds).stream()
                .collect(Collectors.groupingBy(WorkTaskAssignmentEntity::getTaskId));

        return rows.stream().map(row -> {
            List<WorkTaskAssignmentEntity> taskAssignments = byTask.getOrDefault(row.getId(), List.of());
            long completed = taskAssignments.stream()
                    .filter(assignment -> assignment.getStatus() == TaskAssignmentStatus.COMPLETED).count();
            return new TaskDtos.MatrixRow(
                taskResponse(row, mine.get(row.getId()), names, actor, taskAssignments.size(), completed),
                taskAssignments.stream().map(assignment -> assignmentResponse(assignment,
                                names.getOrDefault(assignment.getMembershipId(), "已停用成员")))
                        .toList()
            );
        }).toList();
    }

    @Transactional
    public TaskDtos.TaskResponse create(TaskDtos.CreateRequest body) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        String title = required(body.title(), "TASK_TITLE_REQUIRED", "任务标题不能为空");
        String description = required(body.description(), "TASK_DESCRIPTION_REQUIRED", "任务说明不能为空");
        if (!body.dueAt().isAfter(body.startAt())) {
            throw ApiException.badRequest("INVALID_TASK_PERIOD", "截止时间必须晚于开始时间");
        }
        if (body.dueAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("TASK_DUE_IN_PAST", "不能创建截止时间已过的任务");
        }
        Integer claimLimit = body.distributionType() == TaskDistributionType.SELF_CLAIM ? body.maxClaimants() : null;
        WorkTaskEntity task = tasks.save(new WorkTaskEntity(actor.organizationId(), title, description,
                body.distributionType(), body.startAt(), body.dueAt(), claimLimit, actor.membershipId()));
        Set<UUID> eligible = eligibleMembers(actor.organizationId());
        if (body.distributionType() == TaskDistributionType.MANDATORY) {
            eligible.forEach(memberId -> assignments.save(new WorkTaskAssignmentEntity(
                    actor.organizationId(), task.getId(), memberId, TaskAssignmentSource.MANDATORY)));
        } else if (body.distributionType() == TaskDistributionType.ASSIGNED) {
            Set<UUID> requested = body.assigneeIds() == null ? Set.of() : new HashSet<>(body.assigneeIds());
            if (requested.isEmpty()) {
                throw ApiException.badRequest("TASK_ASSIGNEE_REQUIRED", "派发性任务必须选择至少一名成员");
            }
            if (!eligible.containsAll(requested)) {
                throw ApiException.badRequest("INVALID_TASK_ASSIGNEE", "包含无效、停用或只读成员");
            }
            requested.forEach(memberId -> assignments.save(new WorkTaskAssignmentEntity(
                    actor.organizationId(), task.getId(), memberId, TaskAssignmentSource.ASSIGNED)));
        }
        audit.record(actor, "WORK_TASK_CREATED", "WORK_TASK", task.getId().toString(), "SUCCESS",
                Map.of("distributionType", body.distributionType().name()));
        return taskResponse(task, assignments.findByTaskIdAndMembershipId(task.getId(), actor.membershipId()).orElse(null),
                memberNames(actor.organizationId()), actor);
    }

    @Transactional
    public TaskDtos.AssignmentResponse claim(UUID taskId) {
        CurrentActor actor = actors.require();
        requireContributor(actor);
        WorkTaskEntity task = tasks.findForUpdate(taskId, actor.organizationId())
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在"));
        if (task.getDistributionType() != TaskDistributionType.SELF_CLAIM) {
            throw ApiException.badRequest("TASK_NOT_CLAIMABLE", "该任务不是自领取任务");
        }
        if (task.getStatus() != WorkTaskStatus.PUBLISHED) throw ApiException.conflict("TASK_CLOSED", "任务已经关闭");
        Instant now = Instant.now();
        if (now.isBefore(task.getStartAt())) throw ApiException.conflict("TASK_NOT_STARTED", "任务尚未开始领取");
        if (now.isAfter(task.getDueAt())) throw ApiException.conflict("TASK_EXPIRED", "任务已经截止");
        var existing = assignments.findByTaskIdAndMembershipId(taskId, actor.membershipId());
        if (existing.isPresent()) return assignmentResponse(existing.get(), actor.displayName());
        long claimed = assignments.countByTaskId(taskId);
        if (task.getMaxClaimants() != null && claimed >= task.getMaxClaimants()) {
            throw ApiException.conflict("TASK_CLAIM_LIMIT_REACHED", "该任务已达到领取人数上限");
        }
        try {
            WorkTaskAssignmentEntity saved = assignments.saveAndFlush(new WorkTaskAssignmentEntity(
                    actor.organizationId(), taskId, actor.membershipId(), TaskAssignmentSource.CLAIMED));
            audit.record(actor, "WORK_TASK_CLAIMED", "WORK_TASK", taskId.toString(), "SUCCESS", null);
            return assignmentResponse(saved, actor.displayName());
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("TASK_ALREADY_CLAIMED", "任务已经领取");
        }
    }

    @Transactional
    public TaskDtos.AssignmentResponse updateMine(UUID taskId, TaskDtos.MemberUpdateRequest body) {
        CurrentActor actor = actors.require();
        requireContributor(actor);
        WorkTaskEntity task = requireTask(taskId, actor.organizationId());
        if (task.getStatus() == WorkTaskStatus.CLOSED) throw ApiException.conflict("TASK_CLOSED", "任务已经关闭");
        WorkTaskAssignmentEntity assignment = assignments.findByTaskIdAndMembershipId(taskId, actor.membershipId())
                .orElseThrow(() -> ApiException.notFound("TASK_ASSIGNMENT_NOT_FOUND", "任务尚未分配或领取"));
        String summary = TextNormalizer.optional(body.resultSummary());
        String evidence = validOptionalUrl(body.evidenceUrl());
        assignment.updateByMember(body.status(), summary, evidence);
        audit.record(actor, "WORK_TASK_UPDATED", "WORK_TASK_ASSIGNMENT", assignment.getId().toString(), "SUCCESS",
                Map.of("status", body.status().name()));
        return assignmentResponse(assignment, actor.displayName());
    }

    @Transactional(readOnly = true)
    public List<TaskDtos.AssignmentResponse> assignmentList(UUID taskId) {
        CurrentActor actor = actors.require();
        requireReviewer(actor);
        requireTask(taskId, actor.organizationId());
        Map<UUID, String> names = memberNames(actor.organizationId());
        List<WorkTaskAssignmentEntity> rows = actor.isAdmin()
                ? assignments.findByTaskIdOrderByUpdatedAtDesc(taskId)
                : assignments.findByTaskIdAndMembershipIdInOrderByUpdatedAtDesc(taskId, managedMemberIds(actor));
        return rows.stream()
                .map(row -> assignmentResponse(row, names.getOrDefault(row.getMembershipId(), "已停用成员"))).toList();
    }

    @Transactional
    public TaskDtos.AssignmentResponse review(UUID taskId, UUID assignmentId, TaskDtos.ReviewRequest body) {
        CurrentActor actor = actors.require();
        requireReviewer(actor);
        requireTask(taskId, actor.organizationId());
        WorkTaskAssignmentEntity assignment = assignments.findByIdAndOrganizationIdAndTaskId(
                        assignmentId, actor.organizationId(), taskId)
                .orElseThrow(() -> ApiException.notFound("TASK_ASSIGNMENT_NOT_FOUND", "任务分配记录不存在"));
        if (!actor.isAdmin() && !managedMemberIds(actor).contains(assignment.getMembershipId())) {
            throw ApiException.forbidden("团队长只能审核本团队成员的任务");
        }
        assignment.review(body.status(), TextNormalizer.optional(body.feedback()));
        audit.record(actor, "WORK_TASK_REVIEWED", "WORK_TASK_ASSIGNMENT", assignmentId.toString(), "SUCCESS",
                Map.of("status", body.status().name()));
        return assignmentResponse(assignment, memberNames(actor.organizationId())
                .getOrDefault(assignment.getMembershipId(), "已停用成员"));
    }

    @Transactional
    public void close(UUID taskId) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        WorkTaskEntity task = requireTask(taskId, actor.organizationId());
        task.close();
        audit.record(actor, "WORK_TASK_CLOSED", "WORK_TASK", taskId.toString(), "SUCCESS", null);
    }

    @Transactional
    public void delete(UUID taskId) {
        CurrentActor actor = actors.require();
        permissions.requireAdmin(actor);
        WorkTaskEntity task = requireTask(taskId, actor.organizationId());
        boolean hasProgress = assignments.existsByTaskIdAndStatusNot(taskId, TaskAssignmentStatus.PENDING);
        boolean hasClaimants = task.getDistributionType() == TaskDistributionType.SELF_CLAIM
                && assignments.countByTaskId(taskId) > 0;
        if (hasProgress || hasClaimants) {
            throw ApiException.conflict("TASK_HAS_PROGRESS", "任务已有成员领取、执行或提交记录，请关闭任务以保留历史");
        }
        audit.record(actor, "WORK_TASK_DELETED", "WORK_TASK", taskId.toString(), "SUCCESS",
                Map.of("title", task.getTitle(), "distributionType", task.getDistributionType().name()));
        tasks.delete(task);
    }

    @Transactional
    public void assignActiveMandatoryTasks(UUID organizationId, UUID membershipId) {
        List<UUID> taskIds = jdbc.query("""
                select id from work_tasks where organization_id=? and distribution_type='MANDATORY'
                and status='PUBLISHED' and due_at>now()
                """, (row, index) -> row.getObject("id", UUID.class), organizationId);
        taskIds.forEach(taskId -> assignments.save(new WorkTaskAssignmentEntity(
                organizationId, taskId, membershipId, TaskAssignmentSource.MANDATORY)));
    }

    private TaskDtos.TaskResponse taskResponse(WorkTaskEntity task, WorkTaskAssignmentEntity mine,
                                               Map<UUID, String> names, CurrentActor actor) {
        Set<UUID> scopedMembers = actor.isTeamManager() && !actor.isAdmin() ? managedMemberIds(actor) : Set.of();
        long count = scopedMembers.isEmpty() ? assignments.countByTaskId(task.getId())
                : assignments.countByTaskIdAndMembershipIdIn(task.getId(), scopedMembers);
        long completed = scopedMembers.isEmpty()
                ? assignments.countByTaskIdAndStatus(task.getId(), TaskAssignmentStatus.COMPLETED)
                : assignments.countByTaskIdAndMembershipIdInAndStatus(
                        task.getId(), scopedMembers, TaskAssignmentStatus.COMPLETED);
        return taskResponse(task, mine, names, actor, count, completed);
    }

    private TaskDtos.TaskResponse taskResponse(WorkTaskEntity task, WorkTaskAssignmentEntity mine,
                                               Map<UUID, String> names, CurrentActor actor,
                                               long count, long completed) {
        Instant now = Instant.now();
        boolean claimable = task.getDistributionType() == TaskDistributionType.SELF_CLAIM
                && task.getStatus() == WorkTaskStatus.PUBLISHED && !now.isBefore(task.getStartAt())
                && !now.isAfter(task.getDueAt()) && mine == null
                && (task.getMaxClaimants() == null || count < task.getMaxClaimants());
        boolean overdue = now.isAfter(task.getDueAt()) && (mine == null || mine.getStatus() != TaskAssignmentStatus.COMPLETED);
        return new TaskDtos.TaskResponse(
                task.getId(), task.getTitle(), task.getDescription(), task.getDistributionType(), task.getStartAt(),
                task.getDueAt(), task.getMaxClaimants(), task.getStatus(), count, completed, overdue, claimable,
                mine == null ? null : assignmentResponse(mine, names.getOrDefault(mine.getMembershipId(), "已停用成员")),
                task.getCreatedAt(), task.getVersion());
    }

    private TaskDtos.AssignmentResponse assignmentResponse(WorkTaskAssignmentEntity row, String name) {
        return new TaskDtos.AssignmentResponse(
                row.getId(), row.getMembershipId(), name, row.getAssignmentSource(), row.getStatus(),
                row.getResultSummary(), row.getEvidenceUrl(), row.getReviewFeedback(), row.getClaimedAt(),
                row.getSubmittedAt(), row.getCompletedAt(), row.getUpdatedAt(), row.getVersion());
    }

    private WorkTaskEntity requireTask(UUID id, UUID organizationId) {
        return tasks.findByIdAndOrganizationId(id, organizationId)
                .orElseThrow(() -> ApiException.notFound("TASK_NOT_FOUND", "任务不存在"));
    }

    private Set<UUID> eligibleMembers(UUID organizationId) {
        return new HashSet<>(jdbc.query("""
                select distinct m.id from memberships m
                join membership_roles mr on mr.membership_id=m.id
                join roles r on r.id=mr.role_id
                where m.organization_id=? and m.status='ACTIVE' and r.code in ('MEMBER','TEAM_MANAGER')
                """, (row, index) -> row.getObject("id", UUID.class), organizationId));
    }

    private Map<UUID, String> memberNames(UUID organizationId) {
        Map<UUID, String> result = new HashMap<>();
        jdbc.query("""
                select m.id, u.display_name from memberships m join user_accounts u on u.id=m.user_id
                where m.organization_id=?
                """, (row, index) -> Map.entry(
                row.getObject("id", UUID.class), row.getString("display_name")), organizationId)
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }

    private String required(String value, String code, String message) {
        String normalized = TextNormalizer.optional(value);
        if (normalized == null) throw ApiException.badRequest(code, message);
        return normalized;
    }

    private void requireContributor(CurrentActor actor) {
        if (!actor.canWriteCustomers()) {
            throw ApiException.forbidden("当前角色不能领取或提交任务");
        }
    }

    private void requireReviewer(CurrentActor actor) {
        if (!actor.canReviewTeamOperations()) {
            throw ApiException.forbidden("只有管理员或团队长可以审核任务");
        }
    }

    private Set<UUID> managedMemberIds(CurrentActor actor) {
        if (actor.managedTeamIds().isEmpty()) return Set.of();
        Set<UUID> result = new HashSet<>();
        actor.managedTeamIds().forEach(teamId -> result.addAll(jdbc.queryForList("""
                select tm.membership_id from team_members tm join teams t on t.id=tm.team_id
                where tm.team_id=? and t.active=true
                """, UUID.class, teamId)));
        return result;
    }

    private String validOptionalUrl(String value) {
        String url = TextNormalizer.optional(value);
        if (url == null) return null;
        try {
            URI parsed = URI.create(url);
            if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme())) || parsed.getHost() == null) {
                throw new IllegalArgumentException();
            }
            return url;
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("INVALID_EVIDENCE_URL", "成果链接必须是有效的 HTTP 或 HTTPS 地址");
        }
    }
}
