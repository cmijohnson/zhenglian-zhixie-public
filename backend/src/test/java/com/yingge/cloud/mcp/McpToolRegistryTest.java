package com.yingge.cloud.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.announcement.application.AnnouncementService;
import com.yingge.cloud.assistant.application.AiSettingsService;
import com.yingge.cloud.assistant.application.AssistantFacade;
import com.yingge.cloud.audit.application.AuditQueryService;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.auth.application.RegistrationService;
import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.content.application.ContentService;
import com.yingge.cloud.consultation.application.ConsultationService;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.application.DashboardService;
import com.yingge.cloud.customerpool.application.CustomerPoolService;
import com.yingge.cloud.followup.application.CustomerFollowUpService;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.leadership.application.LeadershipDashboardService;
import com.yingge.cloud.mcp.application.McpApprovalService;
import com.yingge.cloud.mcp.application.McpIdempotencyService;
import com.yingge.cloud.mcp.oauth.McpOAuthService;
import com.yingge.cloud.mcp.security.McpAuthenticationAttributes;
import com.yingge.cloud.mcp.security.McpExecutionContext;
import com.yingge.cloud.mcp.tool.McpToolRegistry;
import com.yingge.cloud.mcp.tool.McpToolResults;
import com.yingge.cloud.market.application.MarketClassificationService;
import com.yingge.cloud.notification.api.GovernanceOverviewController;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.organization.application.OrganizationAdminService;
import com.yingge.cloud.performance.application.PerformanceService;
import com.yingge.cloud.permission.domain.RoleCode;
import com.yingge.cloud.reminder.application.ReminderFacade;
import com.yingge.cloud.report.application.DailyReportService;
import com.yingge.cloud.tag.application.TagApplicationService;
import com.yingge.cloud.task.application.TaskService;
import com.yingge.cloud.teamdata.application.TeamDataService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class McpToolRegistryTest {
    private final McpToolRegistry registry = registry();

    @Test
    void toolSchemasAreExplicitSafeAndRetryable() {
        List<McpServerFeatures.SyncToolSpecification> tools = registry.specifications();
        assertThat(tools).hasSizeGreaterThan(60);
        assertThat(tools.stream().map(spec -> spec.tool().name())).doesNotHaveDuplicates();
        assertThat(tools).extracting(spec -> spec.tool().name()).contains(
                "yingge_identity_me", "yingge_leadership_dashboard_get",
                "yingge_notification_inbox", "yingge_customer_classification_create",
                "yingge_customer_pool_claim", "yingge_second_line_recruitment_claim",
                "yingge_admin_governance_overview")
                .noneMatch(name -> name.contains("business_cycle") || name.contains("deal_forecast"));
        for (var spec : tools) {
            assertThat(spec.tool().inputSchema().additionalProperties()).isFalse();
            assertThat(spec.tool().inputSchema().properties()).doesNotContainKey("organizationId");
            if (Boolean.TRUE.equals(spec.tool().annotations().idempotentHint())) {
                assertThat(spec.tool().inputSchema().properties()).as(spec.tool().name())
                        .containsKey("idempotencyKey");
            }
            if (Boolean.TRUE.equals(spec.tool().annotations().destructiveHint())) {
                assertThat(spec.tool().inputSchema().properties()).as(spec.tool().name())
                        .containsKey("approvalId");
            }
        }
        var safeAi = tools.stream().filter(spec -> spec.tool().name().equals("yingge_admin_ai_settings_update"))
                .findFirst().orElseThrow().tool().inputSchema().properties();
        assertThat(safeAi).doesNotContainKeys("apiKey", "clearApiKey", "organizationId");
    }

    @Test
    void toolsListIsTrimmedByScopeAndCurrentRole() {
        Set<String> allScopes = Set.of("yingge.read", "yingge.write", "yingge.ai", "yingge.admin");
        var admin = registry.visibleSpecifications(context(RoleCode.ORG_ADMIN, allScopes));
        var manager = registry.visibleSpecifications(context(RoleCode.TEAM_MANAGER, allScopes));
        var member = registry.visibleSpecifications(context(RoleCode.MEMBER,
                Set.of("yingge.read", "yingge.write", "yingge.ai")));
        var viewer = registry.visibleSpecifications(context(RoleCode.VIEWER, Set.of("yingge.read")));
        var readonlyAdmin = registry.visibleSpecifications(context(RoleCode.ORG_ADMIN,
                Set.of("yingge.read", "yingge.admin.read")));
        assertThat(admin.size()).isGreaterThan(member.size());
        assertThat(manager).extracting(spec -> spec.tool().name())
                .contains("yingge_customer_lead_review", "yingge_task_assignments", "yingge_task_review",
                        "yingge_content_review")
                .noneMatch(name -> name.startsWith("yingge_admin_"))
                .doesNotContain("yingge_task_matrix", "yingge_task_create");
        assertThat(member).extracting(spec -> spec.tool().name())
                .contains("yingge_customer_search", "yingge_customer_create", "yingge_performance_today",
                        "yingge_performance_claim_create")
                .noneMatch(name -> name.startsWith("yingge_admin_"))
                .noneMatch(name -> name.contains("business_cycle") || name.contains("deal_forecast"))
                .doesNotContain("yingge_task_matrix", "yingge_customer_lead_review");
        assertThat(viewer).extracting(spec -> spec.tool().name())
                .contains("yingge_identity_me", "yingge_customer_search", "yingge_dashboard_get",
                        "yingge_performance_today")
                .doesNotContain("yingge_customer_create", "yingge_ai_daily_briefing",
                        "yingge_performance_claim_create");
        assertThat(readonlyAdmin).extracting(spec -> spec.tool().name())
                .contains("yingge_identity_me", "yingge_leadership_dashboard_get",
                        "yingge_admin_members", "yingge_admin_governance_overview")
                .doesNotContain("yingge_admin_member_create", "yingge_admin_team_create",
                        "yingge_customer_create");
        assertThat(admin).extracting(spec -> spec.tool().name())
                .noneMatch(name -> name.contains("business_cycle") || name.contains("deal_forecast"));
    }

    private McpTransportContext context(RoleCode role, Set<String> scopes) {
        UUID userId = UUID.randomUUID(), membershipId = UUID.randomUUID(), organizationId = UUID.randomUUID();
        CloudPrincipal principal = new CloudPrincipal(userId, membershipId, organizationId,
                "tester", "Tester", "hash", false, Set.of(role), Set.of(), Set.of());
        McpOAuthService.TokenAuthentication token = new McpOAuthService.TokenAuthentication(
                UUID.randomUUID(), UUID.randomUUID(), "client", userId, membershipId, organizationId,
                scopes, 0, 0);
        return McpTransportContext.create(Map.of(McpAuthenticationAttributes.PRINCIPAL, principal,
                McpAuthenticationAttributes.TOKEN, token));
    }

    private McpToolRegistry registry() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        return new McpToolRegistry(mapper, Validation.buildDefaultValidatorFactory().getValidator(),
                mock(McpExecutionContext.class), new McpToolResults(mapper), mock(McpIdempotencyService.class),
                mock(McpApprovalService.class), mock(CurrentActorProvider.class), mock(AuditService.class),
                mock(DashboardService.class), mock(CustomerService.class), mock(CustomerFollowUpService.class),
                mock(ConsultationService.class), mock(InteractionService.class),
                mock(ReminderFacade.class), mock(TaskService.class), mock(ContentService.class),
                mock(AnnouncementService.class), mock(DailyReportService.class), mock(TeamDataService.class),
                mock(TagApplicationService.class), mock(AssistantFacade.class), mock(AiSettingsService.class),
                mock(OrganizationAdminService.class), mock(RegistrationService.class), mock(AuditQueryService.class),
                mock(PerformanceService.class), mock(NotificationService.class),
                mock(GovernanceOverviewController.class), mock(MarketClassificationService.class),
                mock(CustomerPoolService.class), mock(LeadershipDashboardService.class));
    }
}
