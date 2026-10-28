package com.yingge.cloud.mobile.application;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.mobile.api.MobileDtos;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MobileAdminOverviewServiceTest {

    @Test
    void overviewIsOrganizationScopedAndRequiresAdminPermission() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        PermissionService permissions = mock(PermissionService.class);
        UUID organizationId = UUID.randomUUID();
        CurrentActor actor = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), organizationId,
                "admin", "管理员", Set.of(RoleCode.ORG_ADMIN), Set.of(), Set.of());
        MobileDtos.AdminOverview expected = new MobileDtos.AdminOverview(1, 2, 3, 4, 5, 6, 7, 8, 9, Instant.now());
        when(actors.require()).thenReturn(actor);
        when(jdbc.queryForObject(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(expected);

        var result = new MobileAdminOverviewService(jdbc, actors, permissions).overview();

        verify(permissions).requireAdmin(actor);
        assertThat(result.pendingDealRequests()).isEqualTo(1);
        assertThat(result.failedIntelligenceJobs()).isEqualTo(9);
    }
}
