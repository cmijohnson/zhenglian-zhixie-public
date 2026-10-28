package com.yingge.cloud.deal.application;

import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CustomerDealStateProjectorTest {
    @Test
    void projectsAnEntireCustomerPageWithOneSetBasedStateQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NamedParameterJdbcTemplate named = mock(NamedParameterJdbcTemplate.class);
        var projector = new CustomerDealStateProjector(jdbc, named);
        UUID organizationId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        CurrentActor actor = new CurrentActor(UUID.randomUUID(), membershipId, organizationId,
                "member", "成员", Set.of(RoleCode.MEMBER), Set.of(), Set.of());
        List<CustomerEntity> customers = List.of(
                new CustomerEntity(organizationId, membershipId, membershipId),
                new CustomerEntity(organizationId, membershipId, membershipId));

        var responses = projector.projectAll(customers, actor, ignored -> true);

        assertThat(responses).hasSize(2).allSatisfy(response -> {
            assertThat(response.dealState()).isEqualTo("NONE");
            assertThat(response.canRequestDeal()).isTrue();
        });
        verify(named, times(1)).query(anyString(),
                any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class),
                any(org.springframework.jdbc.core.RowCallbackHandler.class));
    }
}
