package com.yingge.cloud.feishu.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.feishu.api.FeishuAuthDtos;
import com.yingge.cloud.feishu.api.FeishuWorkbenchDtos;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import com.yingge.cloud.interaction.api.InteractionDtos;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.api.PageResponse;
import com.yingge.cloud.task.api.TaskDtos;
import com.yingge.cloud.task.application.TaskService;
import com.yingge.cloud.task.domain.TaskAssignmentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class FeishuWorkbenchService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentActorProvider actors;
    private final FeishuAuthService auth;
    private final CustomerService customers;
    private final InteractionService interactions;
    private final TaskService tasks;
    private final FeishuIntegrationService integrations;
    private final FeishuCustomerSnapshotRepository snapshots;
    private final FeishuBitableClient client;

    public FeishuWorkbenchService(JdbcTemplate jdbc, ObjectMapper json, CurrentActorProvider actors,
                                  FeishuAuthService auth, CustomerService customers,
                                  InteractionService interactions, TaskService tasks,
                                  FeishuIntegrationService integrations,
                                  FeishuCustomerSnapshotRepository snapshots,
                                  FeishuBitableClient client) {
        this.jdbc = jdbc;
        this.json = json;
        this.actors = actors;
        this.auth = auth;
        this.customers = customers;
        this.interactions = interactions;
        this.tasks = tasks;
        this.integrations = integrations;
        this.snapshots = snapshots;
        this.client = client;
    }

    @Transactional(readOnly = true)
    public FeishuWorkbenchDtos.BootstrapResponse bootstrap() {
        FeishuAuthService.BindingContext binding = auth.requireActiveBinding();
        var actor = actors.require();
        boolean writable = actor.canWriteCustomers();
        String tableUrl = jdbc.query("""
                select base_app_token,table_id from feishu_bitable_integrations
                where id=? and active=true and schema_state='READY'
                """, (rs, index) -> "https://feishu.cn/base/" + rs.getString(1) + "?table=" + rs.getString(2),
                binding.integrationId()).stream().findFirst().orElse(null);
        Set<String> capabilities = new LinkedHashSet<>(Set.of("CUSTOMER_READ", "INTERACTION_READ", "TASK_READ"));
        if (writable) capabilities.addAll(Set.of("CUSTOMER_DRAFT", "INTERACTION_CREATE", "TASK_OPERATE"));
        if (actor.canReviewTeamOperations()) capabilities.add("TASK_REVIEW");
        if (actor.isAdmin()) capabilities.add("TASK_CLOSE");
        return new FeishuWorkbenchDtos.BootstrapResponse(true, binding.feishuDisplayName(), actor.displayName(),
                writable, actor.hasRole(RoleCode.VIEWER), tableUrl, Set.copyOf(capabilities));
    }

    @Transactional(readOnly = true)
    public PageResponse<CustomerDtos.Response> customers(String search, int page, int size) {
        auth.requireActiveBinding();
        return customers.search(search, null, null, null, null, null, List.of(), false,
                null, null, null, page, size, "updatedDesc");
    }

    @Transactional(readOnly = true)
    public CustomerDtos.Response customer(UUID id) {
        auth.requireActiveBinding();
        return customers.get(id);
    }

    @Transactional(readOnly = true)
    public List<InteractionDtos.Response> interactions(UUID customerId) {
        auth.requireActiveBinding();
        return interactions.list(customerId);
    }

    @Transactional
    public CustomerDtos.Response updateControlledFields(
            UUID id, FeishuWorkbenchDtos.ControlledCustomerUpdateRequest body) {
        auth.requireActiveBinding();
        CustomerDtos.Response current = customers.get(id);
        CustomerDtos.UpsertRequest update = new CustomerDtos.UpsertRequest(
                current.fullName(), current.nickname(), current.phone(), current.wechatId(), current.qqId(),
                current.school(), current.major(), current.grade(), current.city(), current.highSchool(),
                current.sourceLocation(), current.identityType(), current.focusDirections(), current.demandSummary(),
                body.intentionLevel(), body.urgencyLevel(),
                current.verificationStatus(), current.sourceDictionaryId(), current.sourceChannel(),
                body.currentStatus(), current.ownerNotes(),
                body.nextFollowUpAt(), current.friendAddedAt(), current.friendTrustedAt(), current.friendQualifiedAt(),
                current.ownerMembershipId(), current.teamId(), current.visibility(),
                current.tags().stream().map(CustomerDtos.TagSummary::id)
                        .collect(java.util.stream.Collectors.toSet()), null, body.expectedVersion());
        return customers.update(id, update);
    }

    @Transactional
    public FeishuWorkbenchDtos.DraftResponse createDraft(FeishuWorkbenchDtos.CustomerDraftRequest body) {
        FeishuAuthService.BindingContext binding = auth.requireActiveBinding();
        var actor = actors.require();
        if (!actor.canWriteCustomers()) throw ApiException.forbidden("只读账号不能创建客户草稿");
        CustomerDtos.DuplicateResponse duplicate = customers.duplicateCheck(new CustomerDtos.DuplicateRequest(
                body.fullName(), body.major(), body.phone(), body.wechatId(), body.qqId(), null));
        UUID id = UUID.randomUUID();
        Instant expires = Instant.now().plusSeconds(24 * 60 * 60);
        String status = duplicate.blocking() ? "DUPLICATE_BLOCKED" : "CONFIRM_REQUIRED";
        jdbc.update("""
                insert into feishu_customer_drafts
                (id,integration_id,organization_id,record_id,created_by_membership_id,status,safe_payload,
                 duplicate_result,expires_at)
                values (?,?,?,?,?,?,?::jsonb,?::jsonb,?)
                """, id, binding.integrationId(), actor.organizationId(), "workbench-" + id,
                actor.membershipId(), status, write(body), write(duplicate), Timestamp.from(expires));
        return new FeishuWorkbenchDtos.DraftResponse(id, status, duplicate, 0, expires);
    }

    @Transactional
    public CustomerDtos.Response confirmDraft(UUID draftId, FeishuWorkbenchDtos.ConfirmDraftRequest confirmation) {
        FeishuAuthService.BindingContext binding = auth.requireActiveBinding();
        var actor = actors.require();
        DraftRow draft = jdbc.query("""
                select id,integration_id,record_id,status,safe_payload,version,expires_at
                from feishu_customer_drafts
                where id=? and organization_id=? and created_by_membership_id=? for update
                """, (rs, index) -> new DraftRow(rs.getObject("id", UUID.class),
                rs.getObject("integration_id", UUID.class), rs.getString("record_id"), rs.getString("status"),
                rs.getString("safe_payload"), rs.getLong("version"), rs.getTimestamp("expires_at").toInstant()),
                draftId, actor.organizationId(), actor.membershipId()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("FEISHU_CUSTOMER_DRAFT_NOT_FOUND", "客户建档草稿不存在"));
        if (!binding.integrationId().equals(draft.integrationId())) {
            throw ApiException.forbidden("飞书草稿与当前绑定不属于同一集成");
        }
        if (draft.expiresAt().isBefore(Instant.now())) {
            jdbc.update("update feishu_customer_drafts set status='EXPIRED',updated_at=now() where id=?", draft.id());
            throw ApiException.conflict("FEISHU_CUSTOMER_DRAFT_EXPIRED", "客户建档草稿已经过期");
        }
        if (!("CONFIRM_REQUIRED".equals(draft.status()) || "DUPLICATE_BLOCKED".equals(draft.status()))) {
            throw ApiException.conflict("FEISHU_CUSTOMER_DRAFT_FINALIZED", "客户建档草稿已经处理");
        }
        if (confirmation.expectedVersion() != draft.version()) {
            throw ApiException.conflict("FEISHU_CUSTOMER_DRAFT_VERSION_CONFLICT", "客户建档草稿已被修改");
        }
        FeishuWorkbenchDtos.CustomerDraftRequest body = read(draft.payload(), FeishuWorkbenchDtos.CustomerDraftRequest.class);
        boolean quickBlank = "飞书临时建档".equals(body.sourceChannel())
                && blank(body.phone()) && blank(body.wechatId()) && blank(body.qqId());
        CustomerDtos.Response created = quickBlank
                ? customers.createFromFeishuWorkbench(body.fullName(), body.school(),
                        confirmation.ownerMembershipId(), confirmation.duplicateOverrideReason())
                : customers.create(new CustomerDtos.UpsertRequest(
                        body.fullName(), body.nickname(), body.phone(), body.wechatId(), body.qqId(), body.school(),
                        body.major(), body.grade(), body.city(), body.highSchool(), body.sourceLocation(), null,
                        body.focusDirections(), body.demandSummary(), null, null, null, null, body.sourceChannel(), null,
                        null, body.nextFollowUpAt(), null, null, null, confirmation.ownerMembershipId(), confirmation.teamId(),
                        confirmation.visibility(), Set.of(), confirmation.duplicateOverrideReason(), null));
        if (isRemoteRecord(draft.recordId())) {
            attachCreatedCustomerToRemoteDraft(draft, actor.organizationId(), created);
        }
        jdbc.update("""
                update feishu_customer_drafts set status='CREATED',created_customer_id=?,updated_at=now(),version=version+1
                where id=?
                """, created.id(), draft.id());
        return created;
    }

    @Transactional
    public void cancelDraft(UUID draftId) {
        auth.requireActiveBinding();
        var actor = actors.require();
        int changed = jdbc.update("""
                update feishu_customer_drafts set status='CANCELLED',updated_at=now(),version=version+1
                where id=? and organization_id=? and created_by_membership_id=?
                  and status in ('CONFIRM_REQUIRED','DUPLICATE_BLOCKED')
                """, draftId, actor.organizationId(), actor.membershipId());
        if (changed == 0) throw ApiException.notFound("FEISHU_CUSTOMER_DRAFT_NOT_FOUND", "客户建档草稿不存在或已处理");
    }

    public InteractionDtos.Response addInteraction(UUID customerId, InteractionDtos.CreateRequest body) {
        auth.requireActiveBinding();
        return interactions.create(customerId, body);
    }

    public List<TaskDtos.TaskResponse> taskList() {
        auth.requireActiveBinding();
        return tasks.list();
    }

    public List<TaskDtos.AssignmentResponse> taskAssignments(UUID taskId) {
        auth.requireActiveBinding();
        return tasks.assignmentList(taskId);
    }

    public TaskDtos.AssignmentResponse claimTask(UUID taskId) {
        auth.requireActiveBinding();
        return tasks.claim(taskId);
    }

    public TaskDtos.AssignmentResponse updateTask(UUID taskId, TaskDtos.MemberUpdateRequest body) {
        auth.requireActiveBinding();
        return tasks.updateMine(taskId, body);
    }

    public TaskDtos.AssignmentResponse reviewTask(UUID taskId, FeishuWorkbenchDtos.TaskReviewRequest body) {
        auth.requireActiveBinding();
        final TaskAssignmentStatus status;
        try {
            status = TaskAssignmentStatus.valueOf(body.status().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("FEISHU_TASK_REVIEW_STATUS_INVALID", "任务验收状态无效");
        }
        if (!(status == TaskAssignmentStatus.COMPLETED || status == TaskAssignmentStatus.RETURNED)) {
            throw ApiException.badRequest("FEISHU_TASK_REVIEW_STATUS_INVALID", "飞书验收只允许完成或退回");
        }
        return tasks.review(taskId, body.assignmentId(), new TaskDtos.ReviewRequest(status, body.feedback()));
    }

    public void closeTask(UUID taskId) {
        auth.requireActiveBinding();
        tasks.close(taskId);
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to store Feishu draft", exception); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to read Feishu draft", exception); }
    }

    private void attachCreatedCustomerToRemoteDraft(DraftRow draft, UUID organizationId,
                                                     CustomerDtos.Response created) {
        FeishuIntegrationService.RemoteContext remote = integrations.requireRemoteContext(draft.integrationId());
        FeishuCustomerSnapshotRepository.CustomerSnapshot snapshot = snapshots.find(
                organizationId, List.of(created.id())).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Created customer snapshot is missing"));
        Instant syncedAt = Instant.now();
        jdbc.update("""
                insert into feishu_bitable_record_links
                (integration_id,organization_id,customer_id,record_id,source_version,snapshot_hash,synced_at)
                values (?,?,?,?,?,?,?)
                """, draft.integrationId(), organizationId, created.id(), draft.recordId(),
                snapshot.sourceVersion(), snapshot.hash(), Timestamp.from(syncedAt));
        client.batchUpdate(remote.credentials(), remote.integration().baseAppToken(), remote.integration().tableId(),
                List.of(new FeishuBitableClient.RecordUpdate(draft.recordId(),
                        snapshot.remoteFields(remote.remoteNames(), syncedAt))));
    }

    private boolean isRemoteRecord(String recordId) {
        return recordId != null && !recordId.startsWith("workbench-");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record DraftRow(UUID id, UUID integrationId, String recordId, String status, String payload,
                            long version, Instant expiresAt) {}
}
