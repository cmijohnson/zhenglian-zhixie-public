package com.yingge.cloud.mobile.application;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.permission.domain.RoleCode;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MobileBootstrapServiceTest {

    @Test
    void organizationAdminReceivesExplicitAdminModeAndFineGrainedCapabilities() {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CloudPrincipal principal = principal(Set.of(RoleCode.ORG_ADMIN));
        when(actors.requirePrincipal()).thenReturn(principal);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(4L);

        var result = new MobileBootstrapService(actors, jdbc).bootstrap();

        assertThat(result.availableModes()).containsExactlyInAnyOrder("EMPLOYEE", "ADMIN");
        assertThat(result.capabilities()).contains(
                "ADMIN_OVERVIEW_READ", "ADMIN_MEMBERS_WRITE", "ADMIN_TEAMS_WRITE",
                "ADMIN_DEAL_REVIEW", "ADMIN_PERFORMANCE_WRITE", "ADMIN_AUDIT_READ",
                "ADMIN_FOLLOWUP_POLICY_WRITE", "ADMIN_CONSULTATION_SETTINGS_WRITE",
                "LEADERSHIP_DASHBOARD_READ", "BULK_TRANSFER");
    }

    @Test
    void teamManagerKeepsEmployeeModeAndReadOnlyTeamDashboardWithoutAdminCapabilities() {
        CurrentActorProvider actors = mock(CurrentActorProvider.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CloudPrincipal principal = principal(Set.of(RoleCode.TEAM_MANAGER));
        when(actors.requirePrincipal()).thenReturn(principal);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any())).thenReturn(0L);

        var result = new MobileBootstrapService(actors, jdbc).bootstrap();

        assertThat(result.availableModes()).containsExactly("EMPLOYEE");
        assertThat(result.capabilities()).contains("TEAM_DATA", "LEADERSHIP_DASHBOARD_READ", "BULK_TRANSFER");
        assertThat(result.capabilities()).noneMatch(value -> value.startsWith("ADMIN_"));
    }

    private CloudPrincipal principal(Set<RoleCode> roles) {
        return new CloudPrincipal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "mobile-test", "移动测试", "", false, roles, Set.of(), Set.of());
    }
}
