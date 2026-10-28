package com.yingge.cloud.feishu.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.domain.CustomerVisibility;
import com.yingge.cloud.feishu.api.FeishuWorkbenchDtos;
import com.yingge.cloud.feishu.infrastructure.FeishuBitableClient;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.tag.application.TagApplicationService;
import com.yingge.cloud.task.application.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuBidirectionalConsistencyTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deletedBitableRowIsRecreatedAndRelinkedInsteadOfUpdatingADeletedRecord() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        FeishuIntegrationService integrations = mock(FeishuIntegrationService.class);
        FeishuBitableClient client = mock(FeishuBitableClient.class);
        FeishuCustomerSnapshotRepository snapshots = mock(FeishuCustomerSnapshotRepository.class);
        UUID inboxId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String oldRecordId = "recOldRecord123";
        String newRecordId = "recRestoredRecord123";

        ResultSet inboxRow = mock(ResultSet.class);
        when(inboxRow.getObject("id", UUID.class)).thenReturn(inboxId);
        when(inboxRow.getObject("integration_id", UUID.class)).thenReturn(integrationId);
        when(inboxRow.getObject("organization_id", UUID.class)).thenReturn(organizationId);
        when(inboxRow.getString("tenant_key")).thenReturn("tenant-1");
        when(inboxRow.getString("record_id")).thenReturn(oldRecordId);
        when(inboxRow.getString("action_kind")).thenReturn("RECORD_DELETED");
        when(inboxRow.getString("status")).thenReturn("RECEIVED");
        when(jdbc.query(contains("from feishu_event_inbox"), any(RowMapper.class), eq(inboxId)))
                .thenAnswer(invocation -> List.of(((RowMapper) invocation.getArgument(1)).mapRow(inboxRow, 0)));

        ResultSet linkRow = mock(ResultSet.class);
        when(linkRow.getObject(1, UUID.class)).thenReturn(customerId);
        when(linkRow.getLong(2)).thenReturn(7L);
        when(linkRow.getString(3)).thenReturn("before-hash");
        when(jdbc.query(contains("from feishu_bitable_record_links"), any(RowMapper.class),
                eq(integrationId), eq(oldRecordId)))
                .thenAnswer(invocation -> List.of(((RowMapper) invocation.getArgument(1)).mapRow(linkRow, 0)));

        FeishuIntegrationService.IntegrationRow integration = integration(integrationId, organizationId);
        FeishuBitableClient.Credentials credentials = new FeishuBitableClient.Credentials(
                integrationId, "cli_example", "secret-value");
        Map<String, String> remoteNames = remoteNames();
        when(integrations.requireRemoteContext(integrationId)).thenReturn(
                new FeishuIntegrationService.RemoteContext(integration, credentials, remoteNames));
        LinkedHashMap<String, Object> values = canonicalValues(customerId, 7L);
        FeishuCustomerSnapshotRepository.CustomerSnapshot snapshot =
                new FeishuCustomerSnapshotRepository.CustomerSnapshot(customerId, 7L, values, "after-hash");
        when(snapshots.find(organizationId, List.of(customerId))).thenReturn(List.of(snapshot));
        when(client.batchCreate(eq(credentials), eq("base_token"), eq("table_id"), any()))
                .thenReturn(List.of(newRecordId));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        FeishuInboundProcessor processor = new FeishuInboundProcessor(
                jdbc, new ObjectMapper(), integrations, client, snapshots, mock(FeishuActorExecutor.class),
                mock(CustomerService.class), mock(TagApplicationService.class), mock(CurrentActorProvider.class));
        processor.processOne(inboxId);

        verify(client).batchCreate(eq(credentials), eq("base_token"), eq("table_id"), any());
        verify(jdbc).update(contains("set record_id=?"), eq(newRecordId), eq(7L), eq("after-hash"),
                any(Timestamp.class), eq(integrationId), eq(customerId), eq(oldRecordId));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void confirmingAnInboundDraftReusesAndCanonicalizesItsOriginalBitableRow() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        FeishuAuthService auth = mock(FeishuAuthService.class);
        CustomerService customers = mock(CustomerService.class);
        FeishuIntegrationService integrations = mock(FeishuIntegrationService.class);
        FeishuCustomerSnapshotRepository snapshots = mock(FeishuCustomerSnapshotRepository.class);
        FeishuBitableClient client = mock(FeishuBitableClient.class);
        UUID draftId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String recordId = "recInboundDraft123";
        CurrentActor actor = new CurrentActor(UUID.randomUUID(), membershipId, organizationId, "member",
                "测试成员", Set.of(RoleCode.MEMBER), Set.of(), Set.of());
        FeishuWorkbenchDtos.CustomerDraftRequest payload = new FeishuWorkbenchDtos.CustomerDraftRequest(
                "测试客户", null, "13900000001", null, null, "示例大学", null,
                "计算机", "2026", null, null, "待确认需求", Set.of(), "飞书", null);

        when(auth.requireActiveBinding()).thenReturn(new FeishuAuthService.BindingContext(
                integrationId, organizationId, membershipId, "tenant-1", "user-1", "飞书成员"));
        when(actors.require()).thenReturn(actor);
        ResultSet draftRow = mock(ResultSet.class);
        when(draftRow.getObject("id", UUID.class)).thenReturn(draftId);
        when(draftRow.getObject("integration_id", UUID.class)).thenReturn(integrationId);
        when(draftRow.getString("record_id")).thenReturn(recordId);
        when(draftRow.getString("status")).thenReturn("CONFIRM_REQUIRED");
        when(draftRow.getString("safe_payload")).thenReturn(json.writeValueAsString(payload));
        when(draftRow.getLong("version")).thenReturn(0L);
        when(draftRow.getTimestamp("expires_at")).thenReturn(Timestamp.from(Instant.now().plusSeconds(3600)));
        when(jdbc.query(contains("from feishu_customer_drafts"), any(RowMapper.class),
                eq(draftId), eq(organizationId), eq(membershipId)))
                .thenAnswer(invocation -> List.of(((RowMapper) invocation.getArgument(1)).mapRow(draftRow, 0)));

        CustomerDtos.Response created = customer(customerId, membershipId, null, 1L);
        when(customers.create(any())).thenReturn(created);
        FeishuIntegrationService.IntegrationRow integration = integration(integrationId, organizationId);
        FeishuBitableClient.Credentials credentials = new FeishuBitableClient.Credentials(
                integrationId, "cli_example", "secret-value");
        when(integrations.requireRemoteContext(integrationId)).thenReturn(
                new FeishuIntegrationService.RemoteContext(integration, credentials, remoteNames()));
        FeishuCustomerSnapshotRepository.CustomerSnapshot snapshot =
                new FeishuCustomerSnapshotRepository.CustomerSnapshot(customerId, 1L,
                        canonicalValues(customerId, 1L), "created-hash");
        when(snapshots.find(organizationId, List.of(customerId))).thenReturn(List.of(snapshot));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        FeishuWorkbenchService service = new FeishuWorkbenchService(jdbc, json, actors, auth, customers,
                mock(InteractionService.class), mock(TaskService.class), integrations, snapshots, client);
        CustomerDtos.Response result = service.confirmDraft(draftId,
                new FeishuWorkbenchDtos.ConfirmDraftRequest(membershipId, CustomerVisibility.PRIVATE,
                        null, null, 0L));

        assertThat(result).isSameAs(created);
        verify(jdbc).update(contains("insert into feishu_bitable_record_links"), eq(integrationId),
                eq(organizationId), eq(customerId), eq(recordId), eq(1L), eq("created-hash"), any(Timestamp.class));
        verify(client).batchUpdate(eq(credentials), eq("base_token"), eq("table_id"), any());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void acceptedSafeEditImmediatelyWritesBackCanonicalVersionAndAdvancesTheLink() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper json = new ObjectMapper();
        FeishuIntegrationService integrations = mock(FeishuIntegrationService.class);
        FeishuBitableClient client = mock(FeishuBitableClient.class);
        FeishuCustomerSnapshotRepository snapshots = mock(FeishuCustomerSnapshotRepository.class);
        FeishuActorExecutor actorExecutor = mock(FeishuActorExecutor.class);
        CustomerService customers = mock(CustomerService.class);
        UUID inboxId = UUID.randomUUID();
        UUID integrationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        String recordId = "recEditedRecord123";

        ResultSet inboxRow = mock(ResultSet.class);
        when(inboxRow.getObject("id", UUID.class)).thenReturn(inboxId);
        when(inboxRow.getObject("integration_id", UUID.class)).thenReturn(integrationId);
        when(inboxRow.getObject("organization_id", UUID.class)).thenReturn(organizationId);
        when(inboxRow.getString("tenant_key")).thenReturn("tenant-1");
        when(inboxRow.getString("record_id")).thenReturn(recordId);
        when(inboxRow.getString("action_kind")).thenReturn("RECORD_EDITED");
        when(inboxRow.getString("operator_user_id")).thenReturn("user-1");
        when(inboxRow.getString("status")).thenReturn("RECEIVED");
        when(jdbc.query(contains("from feishu_event_inbox"), any(RowMapper.class), eq(inboxId)))
                .thenAnswer(invocation -> List.of(((RowMapper) invocation.getArgument(1)).mapRow(inboxRow, 0)));

        ResultSet linkRow = mock(ResultSet.class);
        when(linkRow.getObject(1, UUID.class)).thenReturn(customerId);
        when(linkRow.getLong(2)).thenReturn(7L);
        when(linkRow.getString(3)).thenReturn("old-hash");
        when(jdbc.query(contains("from feishu_bitable_record_links"), any(RowMapper.class),
                eq(integrationId), eq(recordId)))
                .thenAnswer(invocation -> List.of(((RowMapper) invocation.getArgument(1)).mapRow(linkRow, 0)));
        when(jdbc.query(contains("from feishu_identity_bindings"), any(RowMapper.class),
                eq(integrationId), eq("tenant-1"), eq("user-1"))).thenReturn((List) List.of(membershipId));

        FeishuIntegrationService.IntegrationRow integration = integration(integrationId, organizationId);
        FeishuBitableClient.Credentials credentials = new FeishuBitableClient.Credentials(
                integrationId, "cli_example", "secret-value");
        when(integrations.requireRemoteContext(integrationId)).thenReturn(
                new FeishuIntegrationService.RemoteContext(integration, credentials, remoteNames()));
        when(client.getRecord(credentials, "base_token", "table_id", recordId)).thenReturn(
                new FeishuBitableClient.RemoteRecord(recordId,
                        json.readTree("{\"昵称\":\"飞书新昵称\",\"英格云数据版本\":\"7\"}")));

        LinkedHashMap<String, Object> beforeValues = canonicalValues(customerId, 7L);
        beforeValues.put("nickname", "旧昵称");
        LinkedHashMap<String, Object> afterValues = canonicalValues(customerId, 8L);
        afterValues.put("nickname", "飞书新昵称");
        FeishuCustomerSnapshotRepository.CustomerSnapshot before =
                new FeishuCustomerSnapshotRepository.CustomerSnapshot(customerId, 7L, beforeValues, "old-hash");
        FeishuCustomerSnapshotRepository.CustomerSnapshot after =
                new FeishuCustomerSnapshotRepository.CustomerSnapshot(customerId, 8L, afterValues, "new-hash");
        when(snapshots.find(organizationId, List.of(customerId))).thenReturn(List.of(before), List.of(after));
        when(customers.get(customerId)).thenReturn(customer(customerId, membershipId, "旧昵称", 7L));
        when(customers.update(eq(customerId), any())).thenReturn(customer(customerId, membershipId, "飞书新昵称", 8L));
        when(actorExecutor.as(eq(membershipId), any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(1)).get());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        FeishuInboundProcessor processor = new FeishuInboundProcessor(
                jdbc, json, integrations, client, snapshots, actorExecutor, customers,
                mock(TagApplicationService.class), mock(CurrentActorProvider.class));
        processor.processOne(inboxId);

        verify(client).batchUpdate(eq(credentials), eq("base_token"), eq("table_id"), any());
        verify(jdbc).update(contains("set source_version=?"), eq(8L), eq("new-hash"), any(Timestamp.class),
                eq(integrationId), eq(customerId), eq(recordId));
    }

    private FeishuIntegrationService.IntegrationRow integration(UUID id, UUID organizationId) {
        return new FeishuIntegrationService.IntegrationRow(id, organizationId, "cli_example", new byte[]{1},
                "base_token", "table_id", "客户", true, false, true, "READY", "tenant-1",
                "CONNECTED", Instant.now(), null, Instant.now(), Instant.now(), Instant.now(), Instant.now(), null,
                1L, Instant.now(), Instant.now());
    }

    private Map<String, String> remoteNames() {
        Map<String, String> result = new LinkedHashMap<>();
        FeishuCustomerSchema.FIELDS.forEach(field -> result.put(field.key(), field.name()));
        return result;
    }

    private LinkedHashMap<String, Object> canonicalValues(UUID customerId, long version) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        FeishuCustomerSchema.FIELDS.forEach(field -> result.put(field.key(), ""));
        result.put("customerId", customerId.toString());
        result.put("sourceVersion", Long.toString(version));
        return result;
    }

    private CustomerDtos.Response customer(UUID customerId, UUID membershipId, String nickname, long version) {
        return new CustomerDtos.Response(
                customerId, membershipId, "测试成员", membershipId, "测试成员", null,
                CustomerVisibility.PRIVATE, "测试客户", nickname, "13900000001", null, null,
                "示例大学", "计算机", "2026", null, null, null, "在校生", Set.of(),
                "待确认需求", "中", "中", "未核实", null, "飞书", "初次接触", null,
                null, null, null, null, null, null, null, null, null, null, null,
                Instant.now(), Instant.now(), version, List.of(), "NONE", true, null);
    }
}
