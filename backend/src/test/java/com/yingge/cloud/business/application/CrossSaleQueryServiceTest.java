package com.yingge.cloud.business.application;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.business.api.CrossSaleDtos;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrossSaleQueryServiceTest {
    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock CurrentActorProvider actors;
    @Mock AuditService audit;

    private CrossSaleQueryService service;
    private CurrentActor admin;

    @BeforeEach
    void setUp() {
        admin = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "admin", "管理员",
                Set.of(RoleCode.ORG_ADMIN), Set.of(), Set.of());
        when(actors.require()).thenReturn(admin);
        service = new CrossSaleQueryService(jdbc, actors, audit);
    }

    @Test
    void crossSaleMeansAnyActivePersonLevelCollaboratorEvenWithoutABusinessCycle() {
        stubSearch();
        service.search(true, null, null, null, null, null, null, 0, 50);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        assertThat(countSql.getValue())
                .contains("from customers customer")
                .contains("customer_access_grants")
                .contains("collaborator_membership.status='ACTIVE'")
                .doesNotContain("grant_row.can_edit")
                .contains("left join lateral")
                .doesNotContain("cycle.cross_sale")
                .doesNotContain("customer_business_participant_snapshots");
    }

    @Test
    void collaboratorFilterUsesTheSelectedActiveMembership() {
        stubSearch();
        UUID collaboratorId = UUID.randomUUID();
        service.search(null, null, collaboratorId, null, null, null, null, 0, 50);

        ArgumentCaptor<MapSqlParameterSource> parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(anyString(), parameters.capture(), eq(Long.class));
        assertThat(parameters.getValue().getValue("collaboratorMembershipId")).isEqualTo(collaboratorId);
    }

    @Test
    void organizationAdminWithTeamManagerRoleStillSeesTheWholeOrganization() {
        stubSearch();
        CurrentActor dualRoleAdmin = new CurrentActor(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "admin", "管理员",
                Set.of(RoleCode.ORG_ADMIN, RoleCode.TEAM_MANAGER), Set.of(UUID.randomUUID()), Set.of(UUID.randomUUID()));
        when(actors.require()).thenReturn(dualRoleAdmin);

        service.search(true, null, null, null, null, null, null, 0, 50);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(countSql.capture(), any(MapSqlParameterSource.class), eq(Long.class));
        assertThat(countSql.getValue()).doesNotContain("customer.team_id in (:managedTeamIds)");
    }

    @Test
    void teamManagerWithoutAdminRoleIsRestrictedToManagedTeams() {
        stubSearch();
        UUID managedTeamId = UUID.randomUUID();
        CurrentActor teamManager = new CurrentActor(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "manager", "团队长",
                Set.of(RoleCode.TEAM_MANAGER), Set.of(UUID.randomUUID()), Set.of(managedTeamId));
        when(actors.require()).thenReturn(teamManager);

        service.search(true, null, null, null, null, null, null, 0, 50);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(countSql.capture(), parameters.capture(), eq(Long.class));
        assertThat(countSql.getValue()).contains("customer.team_id in (:managedTeamIds)");
        assertThat(parameters.getValue().getValue("managedTeamIds")).isEqualTo(Set.of(managedTeamId));
    }

    @Test
    void exportUsesTheSameFiltersAndCreatesAReadableWorkbookWithoutPagination() throws Exception {
        UUID collaboratorId = UUID.randomUUID();
        CrossSaleDtos.Response item = new CrossSaleDtos.Response(
                UUID.randomUUID(), "客户甲", "A", UUID.randomUUID(), "一组",
                UUID.randomUUID(), "负责人甲", 1, "协作者乙", true,
                null, null, null, null, null, null, null, null, null,
                Instant.parse("2026-08-19T08:00:00Z"));
        doReturn(List.of(item)).when(jdbc)
                .query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));

        byte[] bytes = service.export(true, null, collaboratorId, null, null, null, "A");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<MapSqlParameterSource> parameters = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(sql.capture(), parameters.capture(), any(RowMapper.class));
        assertThat(sql.getValue()).contains("customer_access_grants", "customer_quality")
                .doesNotContain("limit :limit").doesNotContain("offset :offset");
        assertThat(parameters.getValue().getValue("collaboratorMembershipId")).isEqualTo(collaboratorId);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            var sheet = workbook.getSheet("交叉单查询");
            assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("客户姓名");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("客户甲");
            assertThat(sheet.getRow(1).getCell(6).getStringCellValue()).isEqualTo("是");
        }
    }

    private void stubSearch() {
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class))).thenReturn(0L);
        doReturn(List.of()).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
    }
}
