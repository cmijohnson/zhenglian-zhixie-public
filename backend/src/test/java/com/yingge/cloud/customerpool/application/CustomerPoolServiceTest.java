package com.yingge.cloud.customerpool.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customerpool.api.CustomerPoolDtos;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;
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

class CustomerPoolServiceTest {
    private NamedParameterJdbcTemplate jdbc;
    private CurrentActorProvider actors;
    private CustomerPoolService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        actors = mock(CurrentActorProvider.class);
        service = new CustomerPoolService(jdbc, actors, mock(AuditService.class),
                mock(NotificationService.class));
    }

    @Test
    void poolContractShowsCustomerNameButKeepsContactAndOwnerPrivate() {
        List<String> fields = java.util.Arrays.stream(CustomerPoolDtos.PoolEntry.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();

        assertThat(fields).contains("customerName", "school", "grade", "source", "demandSummary", "timeline");
        assertThat(fields).doesNotContain("phone", "wechatId", "qqId", "ownerDisplayName");
    }

    @Test
    void poolListResolvesSourceFromDictionaryOrLegacyColumn() {
        when(actors.require()).thenReturn(actor(RoleCode.ORG_ADMIN));
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        service.list(null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(MapSqlParameterSource.class),
                any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sql.getValue())
                .contains("customer.full_name customer_name", "source_dictionary.label", "customer.source_channel_legacy")
                .doesNotContain("customer.source,");
    }

    @Test
    void previousOwnerCannotWriteWhileCustomerIsPooled() {
        UUID organizationId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID previousOwner = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("status", "POOLED_AB")));

        boolean allowed = service.mayWriteInCurrentOwnershipState(
                organizationId, customerId, previousOwner, false);

        assertThat(allowed).isFalse();
    }

    @Test
    void onlyTemporaryClaimantCanWriteDuringTemporaryWindow() {
        UUID organizationId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID claimant = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(Map.of("status", "TEMP_CLAIMED",
                        "temporary_claim_membership_id", claimant)));

        assertThat(service.mayWriteInCurrentOwnershipState(
                organizationId, customerId, claimant, false)).isTrue();
        assertThat(service.mayWriteInCurrentOwnershipState(
                organizationId, customerId, UUID.randomUUID(), false)).isFalse();
    }

    @Test
    void invalidInteractionTypeCannotConfirmTemporaryClaim() {
        boolean confirmed = service.confirmTemporaryClaim(
                UUID.randomUUID(), UUID.randomUUID(), "INTERNAL_NOTE");

        assertThat(confirmed).isFalse();
        verify(actors, never()).require();
    }

    @Test
    void activeSecondLineNeedsApprovedAuthorizationBeforeDirectContact() {
        CurrentActor actor = actor(RoleCode.MEMBER);
        when(actors.require()).thenReturn(actor);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class)))
                .thenReturn(1, 0);

        assertThatThrownBy(() -> service.requireSecondLineContactAuthorized(
                UUID.randomUUID(), "CUSTOMER_REPLY"))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("SECOND_LINE_CONTACT_AUTH_REQUIRED"));
    }

    @Test
    void viewerCannotBrowseOrClaimPool() {
        when(actors.require()).thenReturn(actor(RoleCode.VIEWER));

        assertThatThrownBy(() -> service.list(null))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.code()).isEqualTo("FORBIDDEN"));
    }

    private static CurrentActor actor(RoleCode role) {
        return new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "pool-test", "Pool Test", Set.of(role), Set.of(), Set.of());
    }
}
