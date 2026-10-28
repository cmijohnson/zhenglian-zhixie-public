package com.yingge.cloud.reminder.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.intelligence.infrastructure.CustomerIntelligenceStore;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.reminder.api.ReminderDtos;
import com.yingge.cloud.reminder.infrastructure.ReminderEntity;
import com.yingge.cloud.reminder.infrastructure.ReminderRepository;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderSourceAnalysisTest {
    @Mock ReminderRepository reminders;
    @Mock CustomerService customers;
    @Mock CurrentActorProvider actors;
    @Mock PermissionService permissions;
    @Mock AuditService audit;
    @Mock CustomerIntelligenceStore intelligence;

    private ReminderFacade facade;
    private CurrentActor actor;
    private CustomerEntity customer;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        UUID organizationId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        actor = new CurrentActor(UUID.randomUUID(), membershipId, organizationId, "synthetic-member",
                "合成成员", Set.of(RoleCode.MEMBER), Set.of(), Set.of());
        customer = org.mockito.Mockito.mock(CustomerEntity.class);
        lenient().when(customer.getId()).thenReturn(customerId);
        lenient().when(customer.getOwnerMembershipId()).thenReturn(membershipId);
        when(actors.require()).thenReturn(actor);
        when(customers.requireVisibleEntity(customerId, actor)).thenReturn(customer);
        lenient().when(reminders.save(any(ReminderEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        facade = new ReminderFacade(reminders, customers, actors, permissions, audit, intelligence);
    }

    @Test
    void createsReminderBoundToSuccessfulCustomerAnalysisAndAuditsProvenance() {
        UUID sourceVersionId = UUID.randomUUID();
        when(intelligence.isSuccessfulVersionForCustomer(actor.organizationId(), customerId, sourceVersionId))
                .thenReturn(true);

        ReminderDtos.Response response = facade.create(new ReminderDtos.CreateRequest(
                customerId, Instant.parse("2026-09-20T01:00:00Z"), "  核实下次沟通时间  ", sourceVersionId));

        verify(customers).requireTimelineEditable(customer, actor);
        assertThat(response.sourceAnalysisVersionId()).isEqualTo(sourceVersionId);
        assertThat(response.title()).isEqualTo("核实下次沟通时间");
        ArgumentCaptor<ReminderEntity> saved = ArgumentCaptor.forClass(ReminderEntity.class);
        verify(reminders).save(saved.capture());
        assertThat(saved.getValue().getSourceAnalysisVersionId()).isEqualTo(sourceVersionId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> details = ArgumentCaptor.forClass(Map.class);
        verify(audit).record(eq(actor), eq("REMINDER_CREATED"), eq("REMINDER"),
                eq(response.id().toString()), eq("SUCCESS"), details.capture());
        assertThat(details.getValue()).containsEntry("customerId", customerId.toString())
                .containsEntry("sourceAnalysisVersionId", sourceVersionId.toString());
    }

    @Test
    void keepsLegacyCreatePayloadCompatibleWhenSourceVersionIsMissing() throws Exception {
        ReminderDtos.CreateRequest legacyBody = new ObjectMapper().findAndRegisterModules().readValue("""
                {"customerId":"%s","remindAt":"2026-09-20T01:00:00Z","title":"原有客户端提醒"}
                """.formatted(customerId), ReminderDtos.CreateRequest.class);
        assertThat(legacyBody.sourceAnalysisVersionId()).isNull();

        ReminderDtos.Response response = facade.create(legacyBody);

        assertThat(response.sourceAnalysisVersionId()).isNull();
        verify(intelligence, never()).isSuccessfulVersionForCustomer(any(), any(), any());
        verify(audit).record(eq(actor), eq("REMINDER_CREATED"), eq("REMINDER"),
                eq(response.id().toString()), eq("SUCCESS"), isNull());
    }

    @Test
    void rejectsMissingCrossTenantOrCrossCustomerAnalysisWithoutSaving() {
        UUID sourceVersionId = UUID.randomUUID();
        when(intelligence.isSuccessfulVersionForCustomer(actor.organizationId(), customerId, sourceVersionId))
                .thenReturn(false);

        assertThatThrownBy(() -> facade.create(new ReminderDtos.CreateRequest(
                customerId, Instant.parse("2026-09-20T01:00:00Z"), "不允许跨客户引用", sourceVersionId)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("SOURCE_ANALYSIS_VERSION_INVALID"));
        verify(reminders, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsReminderCreationForArchivedCustomer() {
        when(customer.getArchivedAt()).thenReturn(Instant.parse("2026-09-15T00:00:00Z"));

        assertThatThrownBy(() -> facade.create(new ReminderDtos.CreateRequest(
                customerId, Instant.parse("2026-09-20T01:00:00Z"), "归档客户提醒", null)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("ARCHIVED_CUSTOMER_REMINDER_FORBIDDEN"));

        verify(reminders, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsReminderCreationWithoutTimelineEditScope() {
        doThrow(ApiException.forbidden("当前客户仅可查看，不能记录沟通"))
                .when(customers).requireTimelineEditable(customer, actor);

        assertThatThrownBy(() -> facade.create(new ReminderDtos.CreateRequest(
                customerId, Instant.parse("2026-09-20T01:00:00Z"), "只读客户提醒", null)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(error.code()).isEqualTo("FORBIDDEN");
                });

        verify(reminders, never()).save(any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsReminderUpdateWithoutTimelineEditScope() {
        UUID reminderId = UUID.randomUUID();
        ReminderEntity existing = org.mockito.Mockito.mock(ReminderEntity.class);
        when(existing.getCustomerId()).thenReturn(customerId);
        when(reminders.findById(reminderId)).thenReturn(Optional.of(existing));
        doThrow(ApiException.forbidden("当前客户仅可查看，不能记录沟通"))
                .when(customers).requireTimelineEditable(customer, actor);

        assertThatThrownBy(() -> facade.update(reminderId,
                new ReminderDtos.UpdateRequest(null, "不得修改", null)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(error.code()).isEqualTo("FORBIDDEN");
                });

        verify(existing, never()).update(any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsReminderUpdateForArchivedCustomer() {
        UUID reminderId = UUID.randomUUID();
        ReminderEntity existing = org.mockito.Mockito.mock(ReminderEntity.class);
        when(existing.getCustomerId()).thenReturn(customerId);
        when(reminders.findById(reminderId)).thenReturn(Optional.of(existing));
        when(customer.getArchivedAt()).thenReturn(Instant.parse("2026-09-15T00:00:00Z"));

        assertThatThrownBy(() -> facade.update(reminderId,
                new ReminderDtos.UpdateRequest(null, "不得修改", null)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("ARCHIVED_CUSTOMER_REMINDER_FORBIDDEN");
                });

        verify(existing, never()).update(any(), any());
        verify(audit, never()).record(any(), any(), any(), any(), any(), any());
    }
}
