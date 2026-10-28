package com.yingge.cloud.business.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.business.api.BusinessCycleDtos;
import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.business.infrastructure.BusinessCycleEntity;
import com.yingge.cloud.business.infrastructure.BusinessCycleEventRepository;
import com.yingge.cloud.business.infrastructure.BusinessCycleParticipantSnapshotEntity;
import com.yingge.cloud.business.infrastructure.BusinessCycleParticipantSnapshotRepository;
import com.yingge.cloud.business.infrastructure.BusinessCycleRepository;
import com.yingge.cloud.business.infrastructure.CustomerReferralRepository;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.domain.CustomerQuality;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.tag.infrastructure.TagEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessCycleServiceTest {
    @Mock BusinessCycleRepository cycles;
    @Mock BusinessCycleParticipantSnapshotRepository snapshots;
    @Mock BusinessCycleEventRepository events;
    @Mock CustomerReferralRepository referrals;
    @Mock CustomerService customers;
    @Mock CurrentActorProvider actors;
    @Mock PermissionService permissions;
    @Mock BusinessMembershipDirectory membershipDirectory;
    @Mock AuditService audit;
    @Mock NotificationService notifications;
    @Mock DealForecastService dealForecasts;
    @Mock JdbcTemplate jdbc;

    private BusinessCycleService service;
    private CurrentActor admin;
    private UUID customerId;
    private UUID primaryId;

    @BeforeEach
    void setUp() {
        UUID organizationId = UUID.randomUUID();
        primaryId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        admin = new CurrentActor(UUID.randomUUID(), primaryId, organizationId, "admin", "管理员",
                Set.of(RoleCode.ORG_ADMIN), Set.of(), Set.of());
        service = new BusinessCycleService(cycles, snapshots, events, referrals, customers, actors, permissions,
                membershipDirectory, audit, notifications, dealForecasts, jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
        when(actors.require()).thenReturn(admin);
    }

    @Test
    void competitorLossRequiresANameOrVerifiableFact() {
        BusinessCycleEntity cycle = openCycle();
        CustomerEntity customer = org.mockito.Mockito.mock(CustomerEntity.class);
        when(customers.requireVisibleEntity(customerId, admin)).thenReturn(customer);
        when(cycles.findByIdAndOrganizationId(cycle.getId(), admin.organizationId())).thenReturn(Optional.of(cycle));

        assertThatThrownBy(() -> service.markLost(customerId, cycle.getId(),
                new BusinessCycleDtos.MarkLostRequest("  ", null, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEATURE_RETIRED"));

        verify(cycles, never()).saveAndFlush(any());
    }

    @Test
    void aClassFormalQuoteNeedsSecondLineOrExplicitAdminException() {
        BusinessCycleEntity cycle = lockPendingCycle(List.of());
        CustomerEntity customer = org.mockito.Mockito.mock(CustomerEntity.class);
        when(customer.getId()).thenReturn(customerId);
        TagEntity tag = org.mockito.Mockito.mock(TagEntity.class);
        when(tag.isActive()).thenReturn(true);
        when(tag.getCategory()).thenReturn(CustomerQuality.CATEGORY);
        when(tag.getName()).thenReturn("A类用户");
        when(customer.getTags()).thenReturn(Set.of(tag));
        when(customers.requireVisibleEntity(customerId, admin)).thenReturn(customer);
        when(cycles.findByIdAndOrganizationId(cycle.getId(), admin.organizationId())).thenReturn(Optional.of(cycle));
        when(membershipDirectory.requireEligibleMembers(admin.organizationId(), Set.of(primaryId)))
                .thenReturn(Map.of(primaryId, "主负责人"));
        when(membershipDirectory.hasActiveSecondLine(admin.organizationId(), customerId)).thenReturn(false);

        assertThatThrownBy(() -> service.confirmQuote(cycle.getId(),
                new BusinessCycleDtos.ConfirmQuoteRequest(primaryId, List.of(), "标准咨询报价",
                        java.time.Instant.now(), null, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEATURE_RETIRED"));

        verify(snapshots, never()).saveAll(anyCollection());
    }

    @Test
    void formalQuoteUsesTheCustomersActiveCollaboratorDefinitionAndCreatesAnImmutableSnapshot() {
        UUID assistantId = UUID.randomUUID();
        BusinessCycleEntity cycle = lockPendingCycle(List.of(assistantId));
        CustomerEntity customer = org.mockito.Mockito.mock(CustomerEntity.class);
        when(customer.getId()).thenReturn(customerId);
        when(customer.getFullName()).thenReturn("测试客户");
        when(customer.getTags()).thenReturn(Set.of());
        when(customers.requireVisibleEntity(customerId, admin)).thenReturn(customer);
        when(cycles.findByIdAndOrganizationId(cycle.getId(), admin.organizationId())).thenReturn(Optional.of(cycle));
        when(membershipDirectory.requireEligibleMembers(admin.organizationId(), Set.of(primaryId, assistantId)))
                .thenReturn(Map.of(primaryId, "主负责人", assistantId, "辅助成员"));
        when(membershipDirectory.notificationRecipients(eq(admin.organizationId()), eq(customerId), anyCollection()))
                .thenReturn(Set.of(primaryId, assistantId));
        when(snapshots.findByBusinessCycleIdAndSnapshotVersionOrderByRoleTypeAscDisplayNameSnapshotAsc(
                cycle.getId(), 1)).thenReturn(List.of());
        when(events.findByBusinessCycleIdOrderByCreatedAtAscIdAsc(cycle.getId())).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(admin.organizationId()), eq(customerId)))
                .thenReturn(true);

        assertThatThrownBy(() -> service.confirmQuote(cycle.getId(),
                new BusinessCycleDtos.ConfirmQuoteRequest(primaryId, List.of(assistantId), "标准咨询报价",
                        java.time.Instant.now(), null, 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEATURE_RETIRED"));
        verify(snapshots, never()).saveAll(anyCollection());
    }

    @Test
    void administratorCanRejectParticipantLockForCorrection() {
        UUID assistantId = UUID.randomUUID();
        BusinessCycleEntity cycle = lockPendingCycle(List.of(assistantId));
        CustomerEntity customer = org.mockito.Mockito.mock(CustomerEntity.class);
        when(customer.getId()).thenReturn(customerId);
        when(customer.getFullName()).thenReturn("测试客户");
        when(customers.requireVisibleEntity(customerId, admin)).thenReturn(customer);
        when(cycles.findByIdAndOrganizationId(cycle.getId(), admin.organizationId())).thenReturn(Optional.of(cycle));
        when(membershipDirectory.notificationRecipients(eq(admin.organizationId()), eq(customerId), anyCollection()))
                .thenReturn(Set.of(primaryId, assistantId));
        when(events.findByBusinessCycleIdOrderByCreatedAtAscIdAsc(cycle.getId())).thenReturn(List.of());

        assertThatThrownBy(() -> service.rejectQuoteLock(cycle.getId(),
                new BusinessCycleDtos.RejectQuoteLockRequest("辅助人员选择有误，请重新申请", 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEATURE_RETIRED"));
        verify(notifications, never()).publish(any(), any(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void directParticipantCorrectionRequiresAffectedMemberConfirmations() {
        UUID newAssistant = UUID.randomUUID();
        BusinessCycleEntity cycle = formallyQuotedCycle(List.of());
        cycle.declareWon("已确认成单事实", admin.membershipId());
        cycle.confirmWon(admin.membershipId());
        CustomerEntity customer = org.mockito.Mockito.mock(CustomerEntity.class);
        assertThatThrownBy(() -> service.reviseParticipants(cycle.getId(),
                new BusinessCycleDtos.ReviseParticipantsRequest(primaryId, List.of(newAssistant),
                        "原辅助成员记录错误", 0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEATURE_RETIRED"));
    }

    private BusinessCycleEntity openCycle() {
        return new BusinessCycleEntity(admin.organizationId(), customerId, 1, "首次业务周期", null,
                "hash", admin.membershipId());
    }

    private BusinessCycleEntity lockPendingCycle(List<UUID> assistants) {
        BusinessCycleEntity cycle = openCycle();
        cycle.requestParticipantLock("报价前确认主辅", primaryId, assistants, admin.membershipId());
        return cycle;
    }

    private BusinessCycleEntity formallyQuotedCycle(List<UUID> assistants) {
        BusinessCycleEntity cycle = lockPendingCycle(assistants);
        cycle.confirmFormalQuote("标准咨询报价", java.time.Instant.now(), !assistants.isEmpty(),
                null, admin.membershipId());
        return cycle;
    }

}
