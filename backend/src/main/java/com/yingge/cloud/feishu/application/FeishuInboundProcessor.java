package com.yingge.cloud.feishu.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.feishu.api.FeishuWorkbenchDtos;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.tag.api.TagController;
import com.yingge.cloud.tag.application.TagApplicationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class FeishuInboundProcessor {
    private static final Set<String> SAFE_FIELDS = Set.of(
            "nickname", "phone", "wechatId", "qqId", "school", "highSchool", "major", "grade", "city",
            "sourceLocation", "demandSummary", "focusDirections", "tags"
    );
    private static final Set<String> NEVER_DIRECT_FIELDS = Set.of(
            "customerId", "fullName", "identityType", "sourceChannel", "quality", "intentionLevel",
            "urgencyLevel", "currentStatus", "dealState", "ownerName", "teamName", "collaborators",
            "lastContactAt", "nextFollowUpAt", "visibility", "privateCustomer", "archived", "createdAt",
            "updatedAt", "sourceVersion"
    );

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final FeishuIntegrationService integrations;
    private final FeishuBitableClient client;
    private final FeishuCustomerSnapshotRepository snapshots;
    private final FeishuActorExecutor actorExecutor;
    private final CurrentActorProvider currentActors;
    private final CustomerService customers;
    private final TagApplicationService tags;

    public FeishuInboundProcessor(JdbcTemplate jdbc, ObjectMapper json, FeishuIntegrationService integrations,
                                  FeishuBitableClient client, FeishuCustomerSnapshotRepository snapshots,
                                  FeishuActorExecutor actors, CustomerService customers,
                                  TagApplicationService tags, CurrentActorProvider currentActors) {
        this.jdbc = jdbc;
        this.json = json;
        this.integrations = integrations;
        this.client = client;
        this.snapshots = snapshots;
        this.actorExecutor = actors;
        this.currentActors = currentActors;
        this.customers = customers;
        this.tags = tags;
    }

    @Transactional
    public void processOne(UUID inboxId) {
        InboxRow event = jdbc.query("select * from feishu_event_inbox where id=? for update", inboxMapper(), inboxId)
                .stream().findFirst().orElse(null);
        if (event == null || !("RECEIVED".equals(event.status()) || "FAILED".equals(event.status()))) return;
        jdbc.update("update feishu_event_inbox set status='PROCESSING',attempt_count=attempt_count+1 where id=?", inboxId);
        FeishuIntegrationService.RemoteContext context = integrations.requireRemoteContext(event.integrationId());
        if (!context.integration().inboundSyncEnabled()) {
            finish(event, "IGNORED", null, null, List.of(), "FEISHU_INBOUND_PAUSED", null, null);
            return;
        }
        LinkRow link = findLink(event.integrationId(), event.recordId());
        UUID actorMembershipId = findActor(event);
        if (event.actionKind().contains("DELETED")) {
            if (link == null) {
                finish(event, "IGNORED", null, actorMembershipId, List.of(), "FEISHU_UNLINKED_DELETE", null, null);
            } else {
                restoreDeleted(event, context, link, actorMembershipId);
            }
            return;
        }

        FeishuBitableClient.RemoteRecord remote = client.getRecord(context.credentials(),
                context.integration().baseAppToken(), context.integration().tableId(), event.recordId());
        Map<String, Object> remoteValues = logicalValues(remote.fields(), context.remoteNames());
        if (link == null) {
            if (actorMembershipId == null) {
                finish(event, "REJECTED", null, null, List.of(), "FEISHU_OPERATOR_NOT_BOUND", null, null);
                return;
            }
            createDraft(event, actorMembershipId, remoteValues);
            return;
        }
        List<FeishuCustomerSnapshotRepository.CustomerSnapshot> found = snapshots.find(
                context.integration().organizationId(), List.of(link.customerId()));
        if (found.isEmpty()) {
            finish(event, "CONFLICT", link.customerId(), actorMembershipId, List.of(),
                    "FEISHU_LOCAL_CUSTOMER_MISSING", null, null);
            return;
        }
        FeishuCustomerSnapshotRepository.CustomerSnapshot local = found.getFirst();
        Set<String> changed = changedFields(local.values(), remoteValues);
        changed.remove("syncedAt");
        if (changed.isEmpty()) {
            finish(event, "IGNORED", link.customerId(), actorMembershipId, List.of(),
                    "FEISHU_SELF_SYNC_EVENT", local.hash(), local.hash());
            return;
        }
        if (actorMembershipId == null) {
            restore(event, context, local, null, "REJECTED", changed, "FEISHU_OPERATOR_NOT_BOUND");
            return;
        }
        String remoteVersion = string(remoteValues.get("sourceVersion"));
        if (!Long.toString(local.sourceVersion()).equals(remoteVersion)) {
            restore(event, context, local, actorMembershipId, "CONFLICT", changed,
                    "FEISHU_SOURCE_VERSION_CONFLICT");
            return;
        }
        Set<String> protectedChanges = new LinkedHashSet<>(changed);
        protectedChanges.retainAll(NEVER_DIRECT_FIELDS);
        if (!protectedChanges.isEmpty()) {
            restore(event, context, local, actorMembershipId, "REJECTED", changed,
                    "FEISHU_CONTROLLED_FIELD_EDIT_REJECTED");
            return;
        }
        Set<String> unknown = new LinkedHashSet<>(changed);
        unknown.removeAll(SAFE_FIELDS);
        if (!unknown.isEmpty()) {
            restore(event, context, local, actorMembershipId, "REJECTED", changed,
                    "FEISHU_UNKNOWN_FIELD_EDIT_REJECTED");
            return;
        }

        final CustomerDtos.Response updated;
        try {
            updated = actorExecutor.as(actorMembershipId,
                    () -> applySafeFields(link.customerId(), remoteValues, changed));
        } catch (ApiException exception) {
            if (exception.status().is5xxServerError()) throw exception;
            String outcome = exception.status().value() == 409 ? "CONFLICT" : "REJECTED";
            restore(event, context, local, actorMembershipId, outcome, changed, exception.code());
            return;
        }
        List<FeishuCustomerSnapshotRepository.CustomerSnapshot> after = snapshots.find(
                context.integration().organizationId(), List.of(link.customerId()));
        if (after.isEmpty()) {
            throw new IllegalStateException("Applied Feishu change but canonical customer snapshot is missing");
        }
        FeishuCustomerSnapshotRepository.CustomerSnapshot canonical = after.getFirst();
        Instant syncedAt = Instant.now();
        client.batchUpdate(context.credentials(), context.integration().baseAppToken(), context.integration().tableId(),
                List.of(new FeishuBitableClient.RecordUpdate(event.recordId(),
                        canonical.remoteFields(context.remoteNames(), syncedAt))));
        int linked = jdbc.update("""
                update feishu_bitable_record_links
                set source_version=?,snapshot_hash=?,synced_at=?
                where integration_id=? and customer_id=? and record_id=?
                """, canonical.sourceVersion(), canonical.hash(), Timestamp.from(syncedAt),
                event.integrationId(), canonical.customerId(), event.recordId());
        if (linked != 1) throw new IllegalStateException("Feishu record link changed while applying inbound event");
        finish(event, "APPLIED", updated.id(), actorMembershipId, List.copyOf(changed), null,
                local.hash(), canonical.hash());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID inboxId, RuntimeException exception) {
        String code = safeError(exception);
        Integer attempts = jdbc.queryForObject("select attempt_count from feishu_event_inbox where id=?", Integer.class, inboxId);
        int count = attempts == null ? 1 : attempts;
        String status = count >= 5 ? "FAILED" : "RECEIVED";
        jdbc.update("""
                update feishu_event_inbox set status=?,last_error_code=?,
                    next_attempt_at=now()+(? * interval '1 minute'),processed_at=case when ?='FAILED' then now() else null end
                where id=?
                """, status, code, Math.min(1 << Math.min(count, 5), 30), status, inboxId);
    }

    @Transactional(readOnly = true)
    public List<UUID> ready(int limit) {
        return jdbc.query("""
                select id from feishu_event_inbox
                where status in ('RECEIVED','FAILED') and next_attempt_at<=now() and attempt_count<5
                order by received_at limit ?
                """, (rs, index) -> rs.getObject(1, UUID.class), Math.min(Math.max(limit, 1), 50));
    }

    private CustomerDtos.Response applySafeFields(UUID customerId, Map<String, Object> remote, Set<String> changed) {
        CustomerDtos.Response current = customers.get(customerId);
        Set<String> focus = changed.contains("focusDirections") ? split(remote.get("focusDirections")) : current.focusDirections();
        Set<UUID> tagIds = current.tags().stream().map(CustomerDtos.TagSummary::id)
                .filter(id -> current.tags().stream().anyMatch(item -> item.id().equals(id) && "客户质量".equals(item.category())))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (changed.contains("tags")) {
            for (String name : split(remote.get("tags"))) {
                if (name.matches("(?i)^[ABC][类級级]?$")) {
                    throw ApiException.badRequest("FEISHU_QUALITY_EDIT_REJECTED", "A/B/C 等级必须在英格云提交结构化依据");
                }
                UUID id = jdbc.query("""
                        select id from tags where organization_id=? and active=true and lower(name)=lower(?)
                        """, (rs, index) -> rs.getObject(1, UUID.class), currentOrganization(), name)
                        .stream().findFirst().orElseGet(() -> UUID.fromString(tags.createTag(
                                new TagController.CreateTagRequest(name, "飞书协同", "#5B7CFA")).id()));
                tagIds.add(id);
            }
        } else {
            current.tags().stream().map(CustomerDtos.TagSummary::id).forEach(tagIds::add);
        }
        CustomerDtos.UpsertRequest body = new CustomerDtos.UpsertRequest(
                current.fullName(), choose(changed, remote, "nickname", current.nickname()),
                choose(changed, remote, "phone", current.phone()), choose(changed, remote, "wechatId", current.wechatId()),
                choose(changed, remote, "qqId", current.qqId()), choose(changed, remote, "school", current.school()),
                choose(changed, remote, "major", current.major()), choose(changed, remote, "grade", current.grade()),
                choose(changed, remote, "city", current.city()), choose(changed, remote, "highSchool", current.highSchool()),
                choose(changed, remote, "sourceLocation", current.sourceLocation()), current.identityType(), focus,
                choose(changed, remote, "demandSummary", current.demandSummary()), current.intentionLevel(),
                current.urgencyLevel(), current.verificationStatus(), current.sourceDictionaryId(), current.sourceChannel(),
                current.currentStatus(), current.ownerNotes(), current.nextFollowUpAt(), current.friendAddedAt(),
                current.friendTrustedAt(), current.friendQualifiedAt(), current.ownerMembershipId(), current.teamId(),
                current.visibility(), tagIds, null, current.version());
        return customers.update(customerId, body);
    }

    private void createDraft(InboxRow event, UUID actorMembershipId, Map<String, Object> values) {
        String fullName = string(values.get("fullName"));
        if (fullName == null) {
            finish(event, "REJECTED", null, actorMembershipId, List.of(), "FEISHU_DRAFT_NAME_REQUIRED", null, null);
            return;
        }
        FeishuWorkbenchDtos.CustomerDraftRequest draft = new FeishuWorkbenchDtos.CustomerDraftRequest(
                fullName, string(values.get("nickname")), string(values.get("phone")), string(values.get("wechatId")),
                string(values.get("qqId")), string(values.get("school")), string(values.get("highSchool")),
                string(values.get("major")), string(values.get("grade")), string(values.get("city")),
                string(values.get("sourceLocation")), string(values.get("demandSummary")),
                split(values.get("focusDirections")), string(values.get("sourceChannel")), null);
        CustomerDtos.DuplicateResponse duplicate = actorExecutor.as(actorMembershipId, () -> customers.duplicateCheck(
                new CustomerDtos.DuplicateRequest(draft.fullName(), draft.major(), draft.phone(), draft.wechatId(),
                        draft.qqId(), null)));
        String status = duplicate.blocking() ? "DUPLICATE_BLOCKED" : "CONFIRM_REQUIRED";
        jdbc.update("""
                insert into feishu_customer_drafts
                (id,integration_id,organization_id,record_id,created_by_membership_id,status,safe_payload,
                 duplicate_result,expires_at)
                values (?,?,?,?,?,?,?::jsonb,?::jsonb,now()+interval '24 hours')
                on conflict (integration_id,record_id) do update set
                    status=excluded.status,safe_payload=excluded.safe_payload,duplicate_result=excluded.duplicate_result,
                    updated_at=now(),version=feishu_customer_drafts.version+1
                """, UUID.randomUUID(), event.integrationId(), event.organizationId(), event.recordId(),
                actorMembershipId, status, write(draft), write(duplicate));
        finish(event, "APPLIED", null, actorMembershipId, List.of("customerDraft"),
                status, null, null);
    }

    private void restoreDeleted(InboxRow event, FeishuIntegrationService.RemoteContext context, LinkRow link,
                                UUID actorMembershipId) {
        List<FeishuCustomerSnapshotRepository.CustomerSnapshot> rows = snapshots.find(
                event.organizationId(), List.of(link.customerId()));
        if (rows.isEmpty()) {
            finish(event, "CONFLICT", link.customerId(), actorMembershipId, List.of(),
                    "FEISHU_LOCAL_CUSTOMER_MISSING", null, null);
            return;
        }
        FeishuCustomerSnapshotRepository.CustomerSnapshot snapshot = rows.getFirst();
        Instant syncedAt = Instant.now();
        List<String> created = client.batchCreate(context.credentials(), context.integration().baseAppToken(),
                context.integration().tableId(), List.of(snapshot.remoteFields(context.remoteNames(), syncedAt)));
        if (created.size() != 1) throw new IllegalStateException("Feishu restore did not return exactly one record");
        String replacementRecordId = created.getFirst();
        int relinked = jdbc.update("""
                update feishu_bitable_record_links
                set record_id=?,source_version=?,snapshot_hash=?,synced_at=?
                where integration_id=? and customer_id=? and record_id=?
                """, replacementRecordId, snapshot.sourceVersion(), snapshot.hash(), Timestamp.from(syncedAt),
                event.integrationId(), snapshot.customerId(), event.recordId());
        if (relinked != 1) throw new IllegalStateException("Feishu record link changed while restoring deleted record");
        finish(event, "RESTORED", snapshot.customerId(), actorMembershipId, List.of(),
                "FEISHU_DELETE_NOT_ALLOWED", snapshot.hash(), snapshot.hash());
    }

    private void restore(InboxRow event, FeishuIntegrationService.RemoteContext context,
                         FeishuCustomerSnapshotRepository.CustomerSnapshot snapshot, UUID actorMembershipId,
                         String outcome, Set<String> changed, String reason) {
        client.batchUpdate(context.credentials(), context.integration().baseAppToken(), context.integration().tableId(),
                List.of(new FeishuBitableClient.RecordUpdate(event.recordId(),
                        snapshot.remoteFields(context.remoteNames(), Instant.now()))));
        finish(event, outcome, snapshot.customerId(), actorMembershipId, List.copyOf(changed), reason,
                snapshot.hash(), snapshot.hash());
    }

    private void finish(InboxRow event, String outcome, UUID customerId, UUID actorMembershipId,
                        List<String> changedFields, String reason, String beforeHash, String afterHash) {
        jdbc.update("""
                update feishu_event_inbox set status=?,last_error_code=?,processed_at=now() where id=?
                """, outcome, reason, event.id());
        jdbc.update("""
                insert into feishu_inbound_changes
                (id,inbox_event_id,integration_id,organization_id,customer_id,record_id,actor_membership_id,
                 outcome,changed_fields,source_version,reason_code,before_hash,after_hash)
                values (?,?,?,?,?,?,?, ?,string_to_array(?,','),
                        (select source_version from feishu_bitable_record_links
                         where integration_id=? and customer_id=?),?,?,?)
                """, UUID.randomUUID(), event.id(), event.integrationId(), event.organizationId(), customerId,
                event.recordId(), actorMembershipId, outcome, String.join(",", changedFields),
                event.integrationId(), customerId, reason, beforeHash, afterHash);
    }

    private UUID findActor(InboxRow event) {
        String sql = event.operatorUserId() != null ? """
                select membership_id from feishu_identity_bindings
                where integration_id=? and tenant_key=? and feishu_user_id=? and active=true
                """ : """
                select membership_id from feishu_identity_bindings
                where integration_id=? and tenant_key=? and open_id=? and active=true
                """;
        String identity = event.operatorUserId() != null ? event.operatorUserId() : event.operatorOpenId();
        if (identity == null || event.tenantKey() == null) return null;
        return jdbc.query(sql, (rs, index) -> rs.getObject(1, UUID.class),
                event.integrationId(), event.tenantKey(), identity).stream().findFirst().orElse(null);
    }

    private LinkRow findLink(UUID integrationId, String recordId) {
        return jdbc.query("""
                select customer_id,source_version,snapshot_hash from feishu_bitable_record_links
                where integration_id=? and record_id=?
                """, (rs, index) -> new LinkRow(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3)),
                integrationId, recordId).stream().findFirst().orElse(null);
    }

    private Map<String, Object> logicalValues(JsonNode fields, Map<String, String> remoteNames) {
        Map<String, Object> values = new LinkedHashMap<>();
        remoteNames.forEach((key, name) -> {
            if (fields.has(name)) values.put(key, scalar(fields.get(name)));
        });
        return values;
    }

    private Object scalar(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isTextual()) return node.asText();
        if (node.isNumber()) return node.numberValue();
        if (node.isBoolean()) return node.booleanValue();
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual()) values.add(item.asText());
                else if (item.hasNonNull("text")) values.add(item.path("text").asText());
                else values.add(item.toString());
            });
            return String.join("、", values);
        }
        return node.toString();
    }

    private Set<String> changedFields(Map<String, Object> local, Map<String, Object> remote) {
        Set<String> changed = new LinkedHashSet<>();
        for (String key : remote.keySet()) {
            if (!same(local.get(key), remote.get(key))) changed.add(key);
        }
        return changed;
    }

    private boolean same(Object left, Object right) {
        if (left == null && (right == null || "".equals(right))) return true;
        if (right == null && "".equals(left)) return true;
        return Objects.equals(String.valueOf(left), String.valueOf(right));
    }

    private String choose(Set<String> changed, Map<String, Object> remote, String key, String current) {
        return changed.contains(key) ? string(remote.get(key)) : current;
    }

    private Set<String> split(Object value) {
        String text = string(value);
        if (text == null) return Set.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(text.split("[、,，;；\\n]"))
                .map(String::trim).filter(item -> !item.isEmpty()).limit(50).forEach(result::add);
        return result;
    }

    private String string(Object value) {
        if (value == null) return null;
        String clean = String.valueOf(value).trim();
        return clean.isEmpty() ? null : clean;
    }

    private UUID currentOrganization() {
        return currentActors.require().organizationId();
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to store Feishu inbound data", exception); }
    }

    private String safeError(RuntimeException exception) {
        if (exception instanceof ApiException api) return api.code();
        if (exception instanceof FeishuBitableClient.FeishuClientException clientError) return clientError.code();
        return "FEISHU_INBOUND_PROCESSING_FAILED";
    }

    private org.springframework.jdbc.core.RowMapper<InboxRow> inboxMapper() {
        return (rs, index) -> new InboxRow(rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
                rs.getObject("organization_id", UUID.class), rs.getString("tenant_key"), rs.getString("record_id"),
                rs.getString("action_kind"), rs.getString("operator_user_id"), rs.getString("operator_open_id"),
                rs.getString("status"));
    }

    private record InboxRow(UUID id, UUID integrationId, UUID organizationId, String tenantKey, String recordId,
                            String actionKind, String operatorUserId, String operatorOpenId, String status) {}
    private record LinkRow(UUID customerId, long sourceVersion, String snapshotHash) {}
}
