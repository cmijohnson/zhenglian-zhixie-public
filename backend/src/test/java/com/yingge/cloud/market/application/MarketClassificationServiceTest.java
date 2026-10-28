package com.yingge.cloud.market.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.customerpool.application.CustomerPoolService;
import com.yingge.cloud.market.api.MarketDtos;
import com.yingge.cloud.market.domain.CustomerClassLevel;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.tag.infrastructure.TagEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketClassificationServiceTest {
    private NamedParameterJdbcTemplate jdbc;
    private CurrentActorProvider actors;
    private CustomerService customers;
    private AuditService audit;
    private MarketClassificationService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        actors = mock(CurrentActorProvider.class);
        customers = mock(CustomerService.class);
        audit = mock(AuditService.class);
        service = new MarketClassificationService(jdbc, new ObjectMapper(), actors,
                new PermissionService(), customers, audit, mock(MarketDeadlineService.class),
                mock(CustomerPoolService.class), mock(NotificationService.class));
    }

    @Test
    void aClassRequiresAtLeastOneConcreteSignal() {
        CurrentActor actor = actor(RoleCode.MEMBER);
        UUID customerId = UUID.randomUUID();
        CustomerEntity customer = visibleCustomer(actor, customerId);
        when(actors.require()).thenReturn(actor);
        when(customers.requireVisibleEntity(customerId, actor)).thenReturn(customer);

        assertThatThrownBy(() -> service.classify(customerId, new MarketDtos.ClassificationRequest(
                CustomerClassLevel.A, "已确认存在真实需求", "需要产品咨询",
                Set.of(), Set.of(UUID.randomUUID()), null)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("A_CLASS_SIGNAL_REQUIRED"));
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void evidenceMustBelongToTheVisibleCustomerTimeline() {
        CurrentActor actor = actor(RoleCode.MEMBER);
        UUID customerId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        CustomerEntity customer = visibleCustomer(actor, customerId);
        when(actors.require()).thenReturn(actor);
        when(customers.requireVisibleEntity(customerId, actor)).thenReturn(customer);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.classify(customerId, new MarketDtos.ClassificationRequest(
                CustomerClassLevel.A, "已确认存在真实需求", "需要产品咨询",
                Set.of("明确约定近期继续沟通"), Set.of(evidenceId), null)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CLASSIFICATION_EVIDENCE_INVALID"));
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void invisibleOrCrossOrganizationCustomerStopsBeforeAnyMarketWrite() {
        CurrentActor actor = actor(RoleCode.MEMBER);
        UUID customerId = UUID.randomUUID();
        when(actors.require()).thenReturn(actor);
        when(customers.requireVisibleEntity(customerId, actor))
                .thenThrow(ApiException.notFound("CUSTOMER_NOT_FOUND", "未找到客户或无权访问"));

        assertThatThrownBy(() -> service.classify(customerId, new MarketDtos.ClassificationRequest(
                CustomerClassLevel.B, "需求真实", "等待决策条件",
                Set.of(), Set.of(UUID.randomUUID()), null)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CUSTOMER_NOT_FOUND"));
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void adminCanOnlySelectARecommendedPendingReview() {
        CurrentActor admin = actor(RoleCode.ORG_ADMIN);
        when(actors.require()).thenReturn(admin);
        when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenReturn(0);

        assertThatThrownBy(() -> service.selectForReview(UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("CLASSIFICATION_REVIEW_NOT_RECOMMENDED"));
        verify(audit, never()).record(any(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void unchangedLegacyQualityTagDoesNotBlockTimelineWrite() {
        UUID organizationId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        TagEntity qualityA = new TagEntity(organizationId, "A类用户", "客户质量", "#16A34A", UUID.randomUUID());

        service.requireQualityTagConsistent(
                organizationId, customerId, Set.of(qualityA), Set.of(qualityA));
    }

    @Test
    void genericTimelineUpdateCannotAddOrSwitchQualityLevel() {
        UUID organizationId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        TagEntity qualityA = new TagEntity(organizationId, "A类用户", "客户质量", "#16A34A", UUID.randomUUID());
        TagEntity qualityB = new TagEntity(organizationId, "B类用户", "客户质量", "#F59E0B", UUID.randomUUID());

        assertThatThrownBy(() -> service.requireQualityTagConsistent(
                organizationId, customerId, Set.of(), Set.of(qualityA)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STRUCTURED_CLASSIFICATION_REQUIRED"));
        assertThatThrownBy(() -> service.requireQualityTagConsistent(
                organizationId, customerId, Set.of(qualityA), Set.of(qualityB)))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("STRUCTURED_CLASSIFICATION_REQUIRED"));

        service.requireQualityTagConsistent(
                organizationId, customerId, Set.of(qualityA), Set.of());
    }

    private CustomerEntity visibleCustomer(CurrentActor actor, UUID customerId) {
        CustomerEntity customer = mock(CustomerEntity.class);
        when(customer.getId()).thenReturn(customerId);
        when(customer.getOrganizationId()).thenReturn(actor.organizationId());
        return customer;
    }

    private CurrentActor actor(RoleCode role) {
        return new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "market-test", "市场规则测试", Set.of(role), Set.of(), Set.of());
    }
}
