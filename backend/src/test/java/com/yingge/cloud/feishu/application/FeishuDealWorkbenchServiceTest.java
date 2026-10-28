package com.yingge.cloud.feishu.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.deal.application.DealConfirmationService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuDealWorkbenchServiceTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void memberDirectoryAcceptsAnEmptySearchWithoutAnUntypedPostgresNull() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        FeishuAuthService auth = mock(FeishuAuthService.class);
        UUID integrationId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        when(auth.requireActiveBinding()).thenReturn(new FeishuAuthService.BindingContext(
                integrationId, organizationId, membershipId, "tenant", "user", "飞书成员"));
        when(actors.require()).thenReturn(new CurrentActor(
                UUID.randomUUID(), membershipId, organizationId, "member", "测试成员",
                Set.of(RoleCode.MEMBER), Set.of(), Set.of()));
        when(jdbc.query(contains("cast(? as text) is null"), any(RowMapper.class),
                eq(organizationId), isNull(), isNull())).thenReturn((List) List.of());

        FeishuDealWorkbenchService service = new FeishuDealWorkbenchService(
                jdbc, new ObjectMapper(), actors, auth, mock(CustomerService.class),
                mock(DealConfirmationService.class));

        assertThat(service.members(null)).isEmpty();
        verify(jdbc).query(contains("cast(? as text) is null"), any(RowMapper.class),
                eq(organizationId), isNull(), isNull());
    }
}
