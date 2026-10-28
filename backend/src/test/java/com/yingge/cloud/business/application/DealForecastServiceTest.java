package com.yingge.cloud.business.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.business.api.DealForecastDtos;
import com.yingge.cloud.business.domain.DealForecastEvidenceType;
import com.yingge.cloud.business.domain.DealForecastStatus;
import com.yingge.cloud.business.infrastructure.BusinessCycleEntity;
import com.yingge.cloud.business.infrastructure.BusinessCycleRepository;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DealForecastServiceTest {
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock CurrentActorProvider actors;
    @Mock PermissionService permissions;
    @Mock CustomerService customers;
    @Mock BusinessCycleRepository cycles;
    @Mock BusinessMembershipDirectory memberships;
    @Mock AuditService audit;
    @Mock NotificationService notifications;

    private DealForecastService service;
    private CurrentActor member;
    private UUID customerId;
    private BusinessCycleEntity cycle;
    private CustomerEntity customer;

    @BeforeEach
    void setUp() {
        UUID organizationId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        member = new CurrentActor(UUID.randomUUID(), membershipId, organizationId, "member", "成员",
                Set.of(RoleCode.MEMBER), Set.of(), Set.of());
        cycle = new BusinessCycleEntity(organizationId, customerId, 1, "首次业务周期", null,
                "cycle-hash", membershipId);
        customer = mock(CustomerEntity.class);
        when(actors.require()).thenReturn(member);
        service = new DealForecastService(jdbc, actors, permissions, customers, cycles, memberships,
                audit, notifications, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void submissionRejectsInteractionFromAnotherCustomer() {
        UUID interactionId = UUID.randomUUID();
        when(customers.requireVisibleEntity(customerId, member)).thenReturn(customer);
        when(cycles.findByIdAndOrganizationId(cycle.getId(), member.organizationId())).thenReturn(Optional.of(cycle));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(0);

        assertThatThrownBy(() -> service.create(customerId, cycle.getId(), validRequest(interactionId)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEATURE_RETIRED"));

        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void submissionRejectsOtherEvidenceWithoutExplanation() {
        when(customers.requireVisibleEntity(customerId, member)).thenReturn(customer);
        when(cycles.findByIdAndOrganizationId(cycle.getId(), member.organizationId())).thenReturn(Optional.of(cycle));
        DealForecastDtos.CreateRequest body = new DealForecastDtos.CreateRequest(
                List.of(DealForecastEvidenceType.OTHER_VERIFIABLE_FACT), null, List.of(UUID.randomUUID()),
                "客户已明确确认方案", LocalDate.now().plusDays(3), null,
                "发送签约材料", Instant.now().plusSeconds(3600), "forecast-other");

        assertThatThrownBy(() -> service.create(customerId, cycle.getId(), body))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEATURE_RETIRED"));
    }

    @Test
    void submissionBindsNextActionAsJdbcTimestamp() {
        UUID interactionId = UUID.randomUUID();
        when(customers.requireVisibleEntity(customerId, member)).thenReturn(customer);
        when(cycles.findByIdAndOrganizationId(cycle.getId(), member.organizationId())).thenReturn(Optional.of(cycle));
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class), eq(UUID.class))).thenReturn(List.of());
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        doReturn(Collections.singletonList(null)).when(jdbc).query(anyString(), any(MapSqlParameterSource.class),
                any(RowMapper.class));

        assertThatThrownBy(() -> service.create(customerId, cycle.getId(), validRequest(interactionId)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEATURE_RETIRED"));
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void administratorConfirmationSynchronizesHighIntentionWithoutChangingCycleState() {
        UUID forecastId = UUID.randomUUID();
        UUID adminMembershipId = UUID.randomUUID();
        CurrentActor admin = new CurrentActor(UUID.randomUUID(), adminMembershipId, member.organizationId(),
                "admin", "管理员", Set.of(RoleCode.ORG_ADMIN), Set.of(), Set.of());
        when(actors.require()).thenReturn(admin);
        when(customers.requireVisibleEntity(customerId, admin)).thenReturn(customer);
        when(cycles.findByIdAndOrganizationId(cycle.getId(), admin.organizationId())).thenReturn(Optional.of(cycle));
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of(Map.of(
                "id", forecastId, "status", "PENDING_REVIEW", "customer_id", customerId,
                "business_cycle_id", cycle.getId(), "submitted_by_membership_id", member.membershipId())));
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(1);
        doReturn(Collections.singletonList(null)).when(jdbc).query(anyString(), any(MapSqlParameterSource.class),
                any(RowMapper.class));

        assertThatThrownBy(() -> service.confirm(forecastId, new DealForecastDtos.ConfirmRequest(0L)))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("FEATURE_RETIRED"));
        verify(customers, never()).syncHighIntentionFromForecast(any(), any(), any());
    }

    @Test
    void administratorSearchSeparatesDynamicWhereClause() {
        CurrentActor admin = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), member.organizationId(),
                "admin", "管理员", Set.of(RoleCode.ORG_ADMIN), Set.of(), Set.of());
        when(actors.require()).thenReturn(admin);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        doReturn(List.of()).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));

        service.search(DealForecastStatus.CONFIRMED, null, null, null, null, null,
                null, null, false, null, 0, 20);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        assertThat(countSql.getValue())
                .contains("organization_id=:organizationId and forecast.status=:status")
                .contains("customer_access_grants")
                .doesNotContain("cycle.cross_sale")
                .doesNotContain("andforecast");
    }

    private DealForecastDtos.CreateRequest validRequest(UUID interactionId) {
        return new DealForecastDtos.CreateRequest(List.of(DealForecastEvidenceType.CUSTOMER_CONFIRMED), null,
                List.of(interactionId), "客户明确表示准备报名", LocalDate.now().plusDays(3), "等待材料",
                "确认报名材料", Instant.now().plusSeconds(3600), "forecast-create");
    }
}
