package com.yingge.cloud.intelligence.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.intelligence.infrastructure.CustomerIntelligenceStore;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerIntelligenceAccessTest {
    @Mock JdbcTemplate jdbc;
    @Mock CurrentActorProvider actors;
    @Mock PermissionService permissions;
    @Mock CustomerService customers;
    @Mock CustomerIntelligenceQueue queue;
    @Mock CustomerIntelligenceStore store;
    @Mock AuditService audit;
    @Mock CustomerIntelligenceRuntimeMonitor runtimeMonitor;
    @Mock CustomerEntity customer;

    private CustomerIntelligenceService service;
    private CurrentActor actor;
    private UUID customerId;

    @BeforeEach
    void setUp() {
        actor = new CurrentActor(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "synthetic-member", "合成成员", Set.of(RoleCode.MEMBER), Set.of(), Set.of());
        customerId = UUID.randomUUID();
        lenient().when(actors.require()).thenReturn(actor);
        service = new CustomerIntelligenceService(
                jdbc, actors, permissions, customers, queue, store, audit, runtimeMonitor);
    }

    @Test
    void rejectsViewerBeforeResolvingCustomer() {
        CurrentActor viewer = new CurrentActor(
                UUID.randomUUID(), UUID.randomUUID(), actor.organizationId(),
                "synthetic-viewer", "合成查看者", Set.of(RoleCode.VIEWER), Set.of(), Set.of());
        when(actors.require()).thenReturn(viewer);
        CustomerIntelligenceService viewerService = new CustomerIntelligenceService(
                jdbc, actors, new PermissionService(), customers, queue, store, audit, runtimeMonitor);

        assertThatThrownBy(() -> viewerService.reanalyze(customerId))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.code()).isEqualTo("FORBIDDEN");
                });

        verify(customers, never()).requireVisibleEntity(customerId, viewer);
        verify(queue, never()).enqueue(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsCustomerOutsideVisibleOrganizationScope() {
        when(customers.requireVisibleEntity(customerId, actor)).thenThrow(
                ApiException.notFound("CUSTOMER_NOT_FOUND", "未找到客户或无权访问"));

        assertThatThrownBy(() -> service.reanalyze(customerId))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.code()).isEqualTo("CUSTOMER_NOT_FOUND");
                });

        verify(queue, never()).enqueue(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsVisibleCustomerWithoutTimelineEditScope() {
        when(customers.requireVisibleEntity(customerId, actor)).thenReturn(customer);
        doThrow(ApiException.forbidden("当前客户仅可查看，不能记录沟通"))
                .when(customers).requireTimelineEditable(customer, actor);

        assertThatThrownBy(() -> service.reanalyze(customerId))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(error.code()).isEqualTo("FORBIDDEN");
                });

        verify(queue, never()).enqueue(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsArchivedCustomerBeforeQueueingAnalysis() {
        when(customers.requireVisibleEntity(customerId, actor)).thenReturn(customer);
        when(customer.getArchivedAt()).thenReturn(Instant.parse("2026-09-15T00:00:00Z"));

        assertThatThrownBy(() -> service.reanalyze(customerId))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(error.code()).isEqualTo("ARCHIVED_CUSTOMER_ANALYSIS_FORBIDDEN");
                });

        verify(customers).requireTimelineEditable(customer, actor);
        verify(queue, never()).enqueue(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(audit, never()).record(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
