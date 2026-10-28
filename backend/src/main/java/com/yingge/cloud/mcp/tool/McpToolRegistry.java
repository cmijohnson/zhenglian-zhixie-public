package com.yingge.cloud.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.announcement.api.AnnouncementDtos;
import com.yingge.cloud.announcement.application.AnnouncementService;
import com.yingge.cloud.assistant.api.AssistantAdminDtos;
import com.yingge.cloud.assistant.api.AssistantDtos;
import com.yingge.cloud.assistant.application.AiSettingsService;
import com.yingge.cloud.assistant.application.AssistantFacade;
import com.yingge.cloud.audit.application.AuditQueryService;
import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.auth.application.RegistrationService;
import com.yingge.cloud.content.api.ContentDtos;
import com.yingge.cloud.content.application.ContentService;
import com.yingge.cloud.content.domain.ContentPlatform;
import com.yingge.cloud.content.domain.ContentReviewStatus;
import com.yingge.cloud.consultation.api.ConsultationDtos;
import com.yingge.cloud.consultation.application.ConsultationService;
import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.application.DashboardService;
import com.yingge.cloud.customerpool.api.CustomerPoolDtos;
import com.yingge.cloud.customerpool.application.CustomerPoolService;
import com.yingge.cloud.followup.application.CustomerFollowUpService;
import com.yingge.cloud.interaction.api.InteractionDtos;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.leadership.application.LeadershipDashboardService;
import com.yingge.cloud.mcp.application.McpApprovalService;
import com.yingge.cloud.mcp.application.McpIdempotencyService;
import com.yingge.cloud.mcp.security.McpExecutionContext;
import com.yingge.cloud.mcp.security.McpAuthenticationAttributes;
import com.yingge.cloud.market.api.MarketDtos;
import com.yingge.cloud.market.application.MarketClassificationService;
import com.yingge.cloud.notification.api.GovernanceOverviewController;
import com.yingge.cloud.notification.application.NotificationService;
import com.yingge.cloud.organization.application.OrganizationAdminService;
import com.yingge.cloud.performance.api.PerformanceDtos;
import com.yingge.cloud.performance.application.PerformanceService;
import com.yingge.cloud.reminder.api.ReminderDtos;
import com.yingge.cloud.reminder.application.ReminderFacade;
import com.yingge.cloud.report.api.DailyReportDtos;
import com.yingge.cloud.report.application.DailyReportService;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.tag.api.TagController;
import com.yingge.cloud.tag.application.TagApplicationService;
import com.yingge.cloud.task.api.TaskDtos;
import com.yingge.cloud.task.application.TaskService;
import com.yingge.cloud.teamdata.application.TeamDataService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.yingge.cloud.mcp.tool.McpToolSchemas.approvalId;
import static com.yingge.cloud.mcp.tool.McpToolSchemas.bool;
import static com.yingge.cloud.mcp.tool.McpToolSchemas.idempotencyKey;
import static com.yingge.cloud.mcp.tool.McpToolSchemas.integer;
import static com.yingge.cloud.mcp.tool.McpToolSchemas.string;
import static com.yingge.cloud.mcp.tool.McpToolSchemas.uuid;

/** Explicit allow-list of business MCP tools. There is deliberately no method/path proxy tool. */
@Component
public class McpToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);
    private static final String READ = "yingge.read";
    private static final String WRITE = "yingge.write";
    private static final String AI = "yingge.ai";
    private static final String ADMIN_READ = "yingge.admin.read";
    private static final String ADMIN = "yingge.admin";

    private final ObjectMapper mapper;
    private final Validator validator;
    private final McpExecutionContext execution;
    private final McpToolResults results;
    private final McpIdempotencyService idempotency;
    private final McpApprovalService approvals;
    private final CurrentActorProvider actors;
    private final AuditService audit;
    private final DashboardService dashboard;
    private final CustomerService customers;
    private final CustomerFollowUpService followUps;
    private final ConsultationService consultations;
    private final InteractionService interactions;
    private final ReminderFacade reminders;
    private final TaskService tasks;
    private final ContentService content;
    private final AnnouncementService announcements;
    private final DailyReportService reports;
    private final TeamDataService teamData;
    private final TagApplicationService tags;
    private final AssistantFacade assistant;
    private final AiSettingsService aiSettings;
    private final OrganizationAdminService organization;
    private final RegistrationService registration;
    private final AuditQueryService auditQuery;
    private final PerformanceService performance;
    private final NotificationService notifications;
    private final GovernanceOverviewController governance;
    private final MarketClassificationService marketClassifications;
    private final CustomerPoolService customerPools;
    private final LeadershipDashboardService leadership;

    public McpToolRegistry(ObjectMapper mapper, Validator validator, McpExecutionContext execution,
                           McpToolResults results, McpIdempotencyService idempotency,
                           McpApprovalService approvals, CurrentActorProvider actors, AuditService audit,
                           DashboardService dashboard, CustomerService customers,
                           CustomerFollowUpService followUps, ConsultationService consultations,
                           InteractionService interactions, ReminderFacade reminders, TaskService tasks,
                           ContentService content, AnnouncementService announcements,
                           DailyReportService reports, TeamDataService teamData, TagApplicationService tags,
                           AssistantFacade assistant, AiSettingsService aiSettings,
                           OrganizationAdminService organization, RegistrationService registration,
                           AuditQueryService auditQuery, PerformanceService performance,
                           NotificationService notifications, GovernanceOverviewController governance,
                           MarketClassificationService marketClassifications,
                           CustomerPoolService customerPools,
                           LeadershipDashboardService leadership) {
        this.mapper = mapper;
        this.validator = validator;
        this.execution = execution;
        this.results = results;
        this.idempotency = idempotency;
        this.approvals = approvals;
        this.actors = actors;
        this.audit = audit;
        this.dashboard = dashboard;
        this.customers = customers;
        this.followUps = followUps;
        this.consultations = consultations;
        this.interactions = interactions;
        this.reminders = reminders;
        this.tasks = tasks;
        this.content = content;
        this.announcements = announcements;
        this.reports = reports;
        this.teamData = teamData;
        this.tags = tags;
        this.assistant = assistant;
        this.aiSettings = aiSettings;
        this.organization = organization;
        this.registration = registration;
        this.auditQuery = auditQuery;
        this.performance = performance;
        this.notifications = notifications;
        this.governance = governance;
        this.marketClassifications = marketClassifications;
        this.customerPools = customerPools;
        this.leadership = leadership;
    }

    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();

        tools.add(read("yingge_identity_me", "读取当前身份", "读取当前授权账号的稳定身份、角色和团队范围。",
                McpToolSchemas.empty(), call -> {
                    var actor = actors.require();
                    return Map.of(
                            "userId", actor.userId(),
                            "membershipId", actor.membershipId(),
                            "organizationId", actor.organizationId(),
                            "username", actor.username(),
                            "displayName", actor.displayName(),
                            "roles", actor.roles().stream().map(Enum::name).sorted().toList(),
                            "teamIds", actor.teamIds(),
                            "managedTeamIds", actor.managedTeamIds());
                }));
        tools.add(read("yingge_dashboard_get", "读取工作台", "读取本人权限范围内的工作台统计和到期客户。",
                McpToolSchemas.empty(), call -> dashboard.dashboard()));
        tools.add(read("yingge_customer_search", "查询客户", "按可见范围搜索、筛选、排序和分页客户。",
                McpToolSchemas.forRecord(CustomerSearchInput.class, Map.of()), call -> {
                    CustomerSearchInput in = input(call, CustomerSearchInput.class);
                    return customers.search(in.search(), in.status(), in.intention(), in.verification(), in.grade(),
                            in.school(), in.tagIds(), booleanValue(in.includeArchived()), in.teamId(), in.membershipId(),
                            in.ownerMembershipId(), number(in.page(), 0), number(in.size(), 30), in.sort());
                }));
        tools.add(read("yingge_customer_get", "读取客户详情", "读取一份可见客户档案。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> customers.get(parseUuid(call.arguments(), "customerId"))));
        tools.add(read("yingge_customer_duplicate_check", "客户查重", "按姓名和联系方式执行隐私安全的客户查重。",
                McpToolSchemas.forRecord(CustomerDtos.DuplicateRequest.class, Map.of()),
                call -> customers.duplicateCheck(input(call, CustomerDtos.DuplicateRequest.class))));
        tools.add(write("yingge_customer_create", "创建客户", "创建客户档案，支持幂等重试。",
                bodySchema(CustomerDtos.UpsertRequest.class, false), false, call ->
                        customers.create(body(call, CustomerDtos.UpsertRequest.class))));
        tools.add(write("yingge_customer_update", "编辑客户", "编辑一份有权修改的客户档案。",
                idBodySchema("customerId", CustomerDtos.UpsertRequest.class, false), false, call ->
                        customers.update(parseUuid(call.arguments(), "customerId"), body(call, CustomerDtos.UpsertRequest.class))));
        tools.add(write("yingge_customer_transfer", "转接客户", "把客户所有权和原负责人待办提醒转接给其他成员，需要人工确认。",
                idBodySchema("customerId", CustomerDtos.TransferOwnershipRequest.class, true), true, call ->
                        customers.transferOwnership(parseUuid(call.arguments(), "customerId"),
                                body(call, CustomerDtos.TransferOwnershipRequest.class))));
        tools.add(write("yingge_customer_archive", "归档客户", "归档客户并取消活跃提醒，需要人工确认。",
                dangerousIdSchema("customerId"), true, call -> {
                    customers.archive(parseUuid(call.arguments(), "customerId")); return done();
                }));
        tools.add(write("yingge_customer_lead_review", "审核有效线索", "管理员或团队长审核客户线索。",
                idBodySchema("customerId", CustomerDtos.LeadReviewRequest.class, false), false, call ->
                        customers.reviewLead(parseUuid(call.arguments(), "customerId"), body(call, CustomerDtos.LeadReviewRequest.class))));
        tools.add(read("yingge_customer_collaboration_get", "读取客户共享", "读取主负责人、协作者和候选成员。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> customers.collaboration(parseUuid(call.arguments(), "customerId"))));
        tools.add(write("yingge_customer_collaboration_replace", "设置客户共享", "主负责人设置共同维护时间线的协作者。",
                idBodySchema("customerId", CustomerDtos.UpdateCollaboratorsRequest.class, false), false, call ->
                        customers.replaceCollaborators(parseUuid(call.arguments(), "customerId"),
                                body(call, CustomerDtos.UpdateCollaboratorsRequest.class))));

        tools.add(read("yingge_notification_inbox", "读取站内通知", "读取本人站内通知和未读数。",
                McpToolSchemas.forRecord(NotificationInboxInput.class, Map.of()), call -> {
                    NotificationInboxInput in = input(call, NotificationInboxInput.class);
                    return notifications.inbox(booleanValue(in.unreadOnly()), number(in.size(), 50));
                }));
        tools.add(write("yingge_notification_mark_read", "标记通知已读", "标记本人的一条通知已读。",
                idWriteSchema("notificationId", false), false, call -> {
                    notifications.markRead(parseUuid(call.arguments(), "notificationId")); return done();
                }));
        tools.add(write("yingge_notification_mark_all_read", "全部标记已读", "将本人当前通知全部标记已读。",
                idempotentEmptySchema(), false, call -> Map.of("updatedCount", notifications.markAllRead())));

        tools.add(read("yingge_customer_classification_history", "读取客户分级历史", "读取结构化 A/B/C 认定及变更历史。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> marketClassifications.history(parseUuid(call.arguments(), "customerId"))));
        tools.add(read("yingge_customer_classification_current", "读取当前客户分级", "读取当前结构化 A/B/C 认定。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> marketClassifications.current(parseUuid(call.arguments(), "customerId"))));
        tools.add(write("yingge_customer_classification_create", "申报客户分级", "使用事实依据申报 A/B/C 结构化分级。",
                idBodySchema("customerId", MarketDtos.ClassificationRequest.class, false), false,
                call -> marketClassifications.classify(parseUuid(call.arguments(), "customerId"),
                        body(call, MarketDtos.ClassificationRequest.class))));

        tools.add(read("yingge_customer_pool_list", "读取脱敏客户公共池", "按 AB 或 C 类读取可认领的脱敏客户。",
                McpToolSchemas.forRecord(PoolListInput.class, Map.of()), call -> {
                    PoolListInput in = input(call, PoolListInput.class);
                    return customerPools.list(in.type() == null || in.type().isBlank() ? null
                            : CustomerPoolService.PoolType.valueOf(in.type().trim().toUpperCase()));
                }));
        tools.add(write("yingge_customer_pool_claim", "临时认领客户", "认领脱敏公共池客户并进入24小时临时权。",
                idBodySchema("entryId", CustomerPoolDtos.ClaimRequest.class, false), false,
                call -> customerPools.claim(parseUuid(call.arguments(), "entryId"),
                        body(call, CustomerPoolDtos.ClaimRequest.class))));
        tools.add(write("yingge_customer_pool_release", "释放客户到公共池", "主负责人主动释放客户，需要人工确认。",
                idBodySchema("customerId", CustomerPoolDtos.ReleaseRequest.class, true), true,
                call -> customerPools.release(parseUuid(call.arguments(), "customerId"),
                        body(call, CustomerPoolDtos.ReleaseRequest.class))));
        tools.add(read("yingge_second_line_recruitment_list", "读取 A 类二线招募", "读取本组织开放的 A 类二线招募。",
                McpToolSchemas.empty(), call -> customerPools.openSecondLineRecruitments()));
        tools.add(write("yingge_second_line_recruitment_claim", "领取 A 类二线", "领取一个 A 类客户二线岗位。",
                idWriteSchema("recruitmentId", false), false,
                call -> customerPools.claimSecondLine(parseUuid(call.arguments(), "recruitmentId"))));
        tools.add(read("yingge_customer_second_line_list", "读取客户二线", "读取客户当前 A 类二线成员。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> customerPools.secondLines(parseUuid(call.arguments(), "customerId"))));
        tools.add(write("yingge_second_line_contact_request", "申请二线直接联系", "A 类二线在直接联系客户前申请授权。",
                idBodySchema("customerId", CustomerPoolDtos.ContactAuthorizationRequest.class, false), false,
                call -> customerPools.requestContactAuthorization(parseUuid(call.arguments(), "customerId"),
                        body(call, CustomerPoolDtos.ContactAuthorizationRequest.class))));
        tools.add(write("yingge_second_line_contact_decide", "处理二线联系申请", "主负责人或管理员处理二线直接联系申请。",
                idBodySchema("authorizationId", CustomerPoolDtos.ContactAuthorizationDecision.class, false), false,
                call -> customerPools.decideContactAuthorization(parseUuid(call.arguments(), "authorizationId"),
                        body(call, CustomerPoolDtos.ContactAuthorizationDecision.class))));

        tools.add(adminRead("yingge_admin_governance_overview", "读取市场治理待办", "管理员读取期限、公共池、二线、抽查、报价和成单待办。",
                McpToolSchemas.empty(), call -> governance.overview()));

        tools.add(read("yingge_follow_up_recruitment_list", "查询重点客户协作招募",
                "查询本组织尚可领取的 A 类客户协作招募及其 CRM 摘要。",
                McpToolSchemas.empty(), call -> followUps.openRecruitments()));
        tools.add(write("yingge_follow_up_recruitment_claim", "领取重点客户协作",
                "领取一个 A 类客户协作名额，并获得同一档案的时间线编辑权限。",
                idWriteSchema("recruitmentId", false), false,
                call -> followUps.claim(parseUuid(call.arguments(), "recruitmentId"))));

        tools.add(read("yingge_consultation_list", "查询咨询交接", "查询本人权限范围内的咨询交接记录。",
                McpToolSchemas.empty(), call -> consultations.list()));
        tools.add(read("yingge_consultation_get", "读取咨询交接", "读取一条有权访问的咨询交接卡及结果。",
                McpToolSchemas.fields(Map.of("consultationId", uuid()), "consultationId"),
                call -> consultations.get(parseUuid(call.arguments(), "consultationId"))));
        tools.add(aiRead("yingge_consultation_draft", "生成咨询交接草稿",
                "只基于现有客户时间线生成带来源引用的咨询交接草稿。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> consultations.draft(parseUuid(call.arguments(), "customerId"))));
        tools.add(write("yingge_consultation_create", "提交咨询交接",
                "提交已人工确认且带来源引用的咨询交接卡，支持幂等重试。",
                bodySchema(ConsultationDtos.CreateRequest.class, false), false,
                call -> consultations.create(body(call, ConsultationDtos.CreateRequest.class))));

        tools.add(read("yingge_interaction_list", "读取沟通时间线", "读取客户统一沟通时间线。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> interactions.list(parseUuid(call.arguments(), "customerId"))));
        tools.add(write("yingge_interaction_create", "记录沟通", "记录沟通，并事务联动客户状态、标签和提醒。",
                idBodySchema("customerId", InteractionDtos.CreateRequest.class, false), false, call ->
                        interactions.create(parseUuid(call.arguments(), "customerId"), body(call, InteractionDtos.CreateRequest.class))));
        tools.add(read("yingge_reminder_list", "查询提醒", "查询本人权限范围内的跟进提醒。",
                McpToolSchemas.empty(), call -> reminders.list()));
        tools.add(write("yingge_reminder_create", "创建提醒", "创建客户跟进提醒。",
                bodySchema(ReminderDtos.CreateRequest.class, false), false,
                call -> reminders.create(body(call, ReminderDtos.CreateRequest.class))));
        tools.add(write("yingge_reminder_update", "更新提醒", "修改或完成一条提醒。",
                idBodySchema("reminderId", ReminderDtos.UpdateRequest.class, false), false, call ->
                        reminders.update(parseUuid(call.arguments(), "reminderId"), body(call, ReminderDtos.UpdateRequest.class))));

        tools.add(read("yingge_task_list", "查询工作任务", "查询任务和本人的分配进度。",
                McpToolSchemas.empty(), call -> tasks.list()));
        tools.add(read("yingge_task_matrix", "读取任务矩阵", "管理员读取任务×成员进度矩阵。",
                McpToolSchemas.empty(), call -> tasks.matrix()));
        tools.add(write("yingge_task_create", "创建工作任务", "管理员创建强制、派发或自领取任务。",
                bodySchema(TaskDtos.CreateRequest.class, false), false,
                call -> tasks.create(body(call, TaskDtos.CreateRequest.class))));
        tools.add(write("yingge_task_claim", "领取任务", "领取开放的自领取任务。",
                idWriteSchema("taskId", false), false,
                call -> tasks.claim(parseUuid(call.arguments(), "taskId"))));
        tools.add(write("yingge_task_submit", "提交任务进度", "更新本人任务结果、证据和状态。",
                idBodySchema("taskId", TaskDtos.MemberUpdateRequest.class, false), false, call ->
                        tasks.updateMine(parseUuid(call.arguments(), "taskId"), body(call, TaskDtos.MemberUpdateRequest.class))));
        tools.add(read("yingge_task_assignments", "查询任务分配", "管理员或团队长读取可审核的成员任务。",
                McpToolSchemas.fields(Map.of("taskId", uuid()), "taskId"),
                call -> tasks.assignmentList(parseUuid(call.arguments(), "taskId"))));
        tools.add(write("yingge_task_review", "审核任务提交", "管理员或团队长退回或验收成员任务。",
                McpToolSchemas.forRecord(TaskReviewInput.class, Map.of("idempotencyKey", idempotencyKey())), false,
                call -> {
                    TaskReviewInput in = input(call, TaskReviewInput.class);
                    return tasks.review(in.taskId(), in.assignmentId(), validated(in.request()));
                }));
        tools.add(write("yingge_task_close", "关闭任务", "管理员关闭任务，需要人工确认。",
                dangerousIdSchema("taskId"), true, call -> { tasks.close(parseUuid(call.arguments(), "taskId")); return done(); }));
        tools.add(write("yingge_task_delete", "删除任务", "删除无进度任务，需要人工确认。",
                dangerousIdSchema("taskId"), true, call -> { tasks.delete(parseUuid(call.arguments(), "taskId")); return done(); }));

        tools.add(read("yingge_content_overview", "读取内容任务概览", "读取周期、方向、本人内容和审核队列。",
                McpToolSchemas.empty(), call -> content.overview()));
        tools.add(read("yingge_content_circle_search", "搜索内容圈子", "按关键字、发送人、平台、审核状态和权重分页。",
                McpToolSchemas.forRecord(ContentCircleInput.class, Map.of()), call -> {
                    ContentCircleInput in = input(call, ContentCircleInput.class);
                    return content.circle(number(in.page(), 0), number(in.size(), 30), in.search(), in.membershipId(),
                            in.platform(), in.reviewStatus(), in.sort());
                }));
        tools.add(read("yingge_content_senders", "读取内容发送人", "读取圈子发送人筛选选项。",
                McpToolSchemas.empty(), call -> content.circleSenders()));
        tools.add(write("yingge_content_direction_create", "创建内容方向", "管理员创建内容方向。",
                bodySchema(ContentDtos.DirectionRequest.class, false), false,
                call -> content.createDirection(body(call, ContentDtos.DirectionRequest.class))));
        tools.add(write("yingge_content_direction_update", "更新内容方向", "管理员更新或停用内容方向。",
                idBodySchema("directionId", ContentDtos.DirectionRequest.class, false), false, call ->
                        content.updateDirection(parseUuid(call.arguments(), "directionId"), body(call, ContentDtos.DirectionRequest.class))));
        tools.add(write("yingge_content_submit", "提交内容", "提交小红书或抖音内容任务。",
                bodySchema(ContentDtos.PostRequest.class, false), false,
                call -> content.submit(body(call, ContentDtos.PostRequest.class))));
        tools.add(write("yingge_content_metrics_update", "更新内容指标", "更新浏览、点赞和收藏指标。",
                idBodySchema("contentId", ContentDtos.MetricsRequest.class, false), false, call ->
                        content.updateMetrics(parseUuid(call.arguments(), "contentId"), body(call, ContentDtos.MetricsRequest.class))));
        tools.add(write("yingge_content_review", "审核内容", "管理员或团队长审核内容有效性。",
                idBodySchema("contentId", ContentDtos.ReviewRequest.class, false), false, call ->
                        content.review(parseUuid(call.arguments(), "contentId"), body(call, ContentDtos.ReviewRequest.class))));

        tools.add(read("yingge_daily_report_today", "读取今日日报", "读取今日日报和自动统计值。",
                McpToolSchemas.empty(), call -> reports.today()));
        tools.add(read("yingge_daily_report_history", "读取日报历史", "读取权限范围内的日报历史。",
                McpToolSchemas.empty(), call -> reports.history()));
        tools.add(read("yingge_daily_report_calendar", "读取日报提交日历", "管理员或团队长按月读取已提交和未提交人员。",
                McpToolSchemas.fields(Map.of("month", string()), "month"),
                call -> reports.calendar(text(call.arguments(), "month"))));
        tools.add(write("yingge_daily_report_submit", "提交日报", "提交指定日期日报。",
                McpToolSchemas.forRecord(DailyReportInput.class, Map.of("idempotencyKey", idempotencyKey())), false,
                call -> { DailyReportInput in = input(call, DailyReportInput.class); return reports.submit(in.date(), validated(in.request())); }));

        tools.add(read("yingge_performance_today", "读取今日绩效", "读取本人今日得分、申报窗口、可申报沟通和积分明细。",
                McpToolSchemas.empty(), call -> performance.today()));
        tools.add(read("yingge_performance_leaderboard", "读取绩效排行", "读取权限范围内指定日期段的团队绩效排行。",
                McpToolSchemas.forRecord(PerformanceRangeInput.class, Map.of()), call -> {
                    PerformanceRangeInput in = input(call, PerformanceRangeInput.class);
                    return performance.leaderboard(in.from(), in.to(), in.teamId());
                }));
        tools.add(read("yingge_performance_history", "读取个人绩效趋势", "读取本人指定日期段的得分趋势。",
                McpToolSchemas.forRecord(PerformanceRangeInput.class, Map.of()), call -> {
                    PerformanceRangeInput in = input(call, PerformanceRangeInput.class);
                    return performance.history(in.from(), in.to());
                }));
        tools.add(read("yingge_performance_claims", "查询交流绩效申报", "查询本人或有权查看范围内的交流绩效申报。",
                McpToolSchemas.forRecord(PerformanceListInput.class, Map.of()), call -> {
                    PerformanceListInput in = input(call, PerformanceListInput.class);
                    return performance.claims(in.status(), in.membershipId(), in.date());
                }));
        tools.add(write("yingge_performance_claim_create", "创建交流绩效申报", "关联本人已记录的客户沟通创建申报；证据图片仍需在网页上传。",
                bodySchema(PerformanceDtos.CreateClaimRequest.class, false), false,
                call -> performance.createClaim(body(call, PerformanceDtos.CreateClaimRequest.class))));
        tools.add(write("yingge_performance_claim_submit", "提交交流绩效申报", "提交已经在网页上传证据的交流绩效草稿。",
                idWriteSchema("claimId", false), false,
                call -> performance.submitClaim(parseUuid(call.arguments(), "claimId"))));
        tools.add(read("yingge_performance_activities", "查询绩效活动", "查询本人或有权查看范围内的活动提案及处理状态。",
                McpToolSchemas.forRecord(PerformanceListInput.class, Map.of()), call -> {
                    PerformanceListInput in = input(call, PerformanceListInput.class);
                    return performance.activities(in.status(), in.membershipId(), in.date());
                }));
        tools.add(write("yingge_performance_activity_create", "创建绩效活动提案", "创建活动提案并等待管理员立项。",
                bodySchema(PerformanceDtos.CreateActivityRequest.class, false), false,
                call -> performance.createActivity(body(call, PerformanceDtos.CreateActivityRequest.class))));
        tools.add(write("yingge_performance_activity_complete", "提交活动成果", "提交已经在网页上传证据的活动实际参与人数。",
                idBodySchema("activityId", PerformanceDtos.CompleteActivityRequest.class, false), false,
                call -> performance.completeActivity(parseUuid(call.arguments(), "activityId"),
                        body(call, PerformanceDtos.CompleteActivityRequest.class))));
        tools.add(read("yingge_announcement_list", "查询公告", "查询公告，可选择包含归档。",
                McpToolSchemas.fields(Map.of("includeArchived", bool())),
                call -> announcements.list(booleanValue(call.arguments().get("includeArchived")))));
        tools.add(write("yingge_announcement_create", "发布公告", "管理员发布公告。",
                bodySchema(AnnouncementDtos.UpsertRequest.class, false), false,
                call -> announcements.create(body(call, AnnouncementDtos.UpsertRequest.class))));
        tools.add(write("yingge_announcement_update", "编辑公告", "管理员编辑公告优先级和置顶状态。",
                idBodySchema("announcementId", AnnouncementDtos.UpsertRequest.class, false), false, call ->
                        announcements.update(parseUuid(call.arguments(), "announcementId"), body(call, AnnouncementDtos.UpsertRequest.class))));
        tools.add(write("yingge_announcement_archive", "归档公告", "管理员归档公告，需要人工确认。",
                dangerousIdSchema("announcementId"), true, call -> {
                    announcements.archive(parseUuid(call.arguments(), "announcementId")); return done();
                }));
        tools.add(read("yingge_team_data_overview", "读取团队数据", "按服务端权限读取团队、成员和客户筛选后的统计。",
                McpToolSchemas.forRecord(TeamDataInput.class, Map.of()), call -> {
                    TeamDataInput in = input(call, TeamDataInput.class);
                    return teamData.overview(in.teamId(), in.membershipId(), in.search(), in.grade());
                }));
        tools.add(read("yingge_leadership_dashboard_get", "读取经营看板", "复用经营看板权限裁剪读取团队经营数据。",
                McpToolSchemas.forRecord(LeadershipDashboardInput.class, Map.of()), call -> {
                    LeadershipDashboardInput in = input(call, LeadershipDashboardInput.class);
                    return leadership.overview(in.teamId(), in.membershipId(), in.days());
                }));

        tools.add(read("yingge_tag_list", "读取标签", "读取组织标签字典。",
                McpToolSchemas.empty(), call -> tags.listTags()));
        tools.add(write("yingge_tag_create", "新增标签", "新增非年级客户标签。",
                bodySchema(TagController.CreateTagRequest.class, false), false,
                call -> tags.createTag(body(call, TagController.CreateTagRequest.class))));
        tools.add(read("yingge_dictionary_list", "读取业务字典", "读取学校、来源、关注方向和沟通渠道。",
                McpToolSchemas.empty(), call -> tags.dictionaries()));
        tools.add(write("yingge_dictionary_create", "新增字典项", "管理员新增受控业务字典项。",
                bodySchema(TagController.CreateDictionaryRequest.class, false), false,
                call -> tags.createDictionary(body(call, TagController.CreateDictionaryRequest.class))));
        tools.add(write("yingge_school_create", "新增学校选项", "新增学校选项，供客户档案选择。",
                bodySchema(TagController.CreateSchoolRequest.class, false), false,
                call -> tags.createSchool(body(call, TagController.CreateSchoolRequest.class))));
        tools.add(write("yingge_source_create", "新增客户来源", "新增小红书、抖音、新生群、运营商等来源选项。",
                bodySchema(TagController.CreateSourceRequest.class, false), false,
                call -> tags.createSource(body(call, TagController.CreateSourceRequest.class))));

        tools.add(aiRead("yingge_ai_availability", "检查智能助手", "读取本组织智能助手可用性和模型，不返回密钥。",
                McpToolSchemas.empty(), call -> assistant.availability()));
        tools.add(ai("yingge_ai_customer_draft", "生成客户草稿", "从文字生成结构化客户草稿。",
                bodySchema(AssistantDtos.ParseRequest.class, false), call ->
                        assistant.parseCustomerDraft(body(call, AssistantDtos.ParseRequest.class).text())));
        tools.add(ai("yingge_ai_interaction_draft", "生成沟通草稿", "从文字生成结构化沟通草稿。",
                bodySchema(AssistantDtos.ParseRequest.class, false), call ->
                        assistant.parseInteractionDraft(body(call, AssistantDtos.ParseRequest.class).text())));
        tools.add(ai("yingge_ai_customer_summary", "总结客户时间线", "总结一名可见客户的时间线。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> Map.of("summary", assistant.summarizeCustomerTimeline(parseUuid(call.arguments(), "customerId")))));
        tools.add(ai("yingge_ai_next_actions", "建议下一步", "基于客户时间线给出下一步建议。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> Map.of("suggestions", assistant.recommendNextActions(parseUuid(call.arguments(), "customerId")))));
        tools.add(ai("yingge_ai_daily_briefing", "生成每日简报", "生成当前员工每日简报。",
                McpToolSchemas.empty(), call -> Map.of("briefing", assistant.generateDailyBriefing())));
        tools.add(ai("yingge_ai_data_quality", "检测数据质量", "检测一名客户的数据质量问题。",
                McpToolSchemas.fields(Map.of("customerId", uuid()), "customerId"),
                call -> Map.of("issues", assistant.detectDataQualityIssues(parseUuid(call.arguments(), "customerId")))));

        tools.add(adminRead("yingge_admin_members", "管理端成员列表", "管理员读取成员、角色、状态和团队。",
                McpToolSchemas.empty(), call -> organization.members()));
        tools.add(adminWrite("yingge_admin_member_create", "创建成员", "管理员创建成员和初始密码。",
                bodySchema(OrganizationAdminService.CreateMemberRequest.class, false), false,
                call -> organization.createMember(body(call, OrganizationAdminService.CreateMemberRequest.class))));
        tools.add(adminWrite("yingge_admin_member_update", "更新成员", "修改成员姓名、角色、状态或团队，需要人工确认。",
                idBodySchema("membershipId", OrganizationAdminService.UpdateMemberRequest.class, true), true,
                call -> organization.updateMember(parseUuid(call.arguments(), "membershipId"),
                        body(call, OrganizationAdminService.UpdateMemberRequest.class))));
        tools.add(adminWrite("yingge_admin_member_delete", "删除成员", "转移客户与提醒后删除成员，需要人工确认。",
                McpToolSchemas.forRecord(DeleteMemberInput.class,
                        Map.of("idempotencyKey", idempotencyKey(), "approvalId", approvalId())), true, call -> {
                    DeleteMemberInput in = input(call, DeleteMemberInput.class);
                    organization.deleteMember(in.membershipId(), in.transferCustomerToMembershipId()); return done();
                }));
        tools.add(adminRead("yingge_admin_teams", "管理端团队列表", "管理员读取团队、团队长和成员。",
                McpToolSchemas.empty(), call -> organization.teams()));
        tools.add(adminWrite("yingge_admin_team_create", "创建团队", "管理员创建团队。",
                McpToolSchemas.fields(Map.of("name", string(), "idempotencyKey", idempotencyKey()), "name"), false,
                call -> organization.createTeam(text(call.arguments(), "name"))));
        tools.add(adminWrite("yingge_admin_team_assign", "分配团队成员", "管理员向团队分配成员或团队管理权限。",
                McpToolSchemas.forRecord(TeamAssignInput.class, Map.of("idempotencyKey", idempotencyKey())), false,
                call -> { TeamAssignInput in = input(call, TeamAssignInput.class);
                    organization.assignTeam(in.teamId(), in.membershipId(), in.manager()); return done(); }));
        tools.add(adminWrite("yingge_admin_team_leader", "设置团队长", "管理员设置唯一团队长。",
                McpToolSchemas.forRecord(TeamLeaderInput.class, Map.of("idempotencyKey", idempotencyKey())), false,
                call -> { TeamLeaderInput in = input(call, TeamLeaderInput.class);
                    return organization.assignLeader(in.teamId(), in.membershipId()); }));
        tools.add(adminWrite("yingge_admin_team_delete", "删除团队", "删除团队但保留成员、客户和历史，需要人工确认。",
                dangerousIdSchema("teamId"), true, call -> {
                    organization.deleteTeam(parseUuid(call.arguments(), "teamId")); return done();
                }));
        tools.add(adminRead("yingge_admin_registration_codes", "查询注册码", "管理员读取脱敏注册码及使用状态。",
                McpToolSchemas.empty(), call -> registration.listCodes()));
        tools.add(adminWrite("yingge_admin_registration_code_create", "创建注册码", "管理员创建限次、可过期注册码。",
                McpToolSchemas.forRecord(RegistrationCodeInput.class, Map.of("idempotencyKey", idempotencyKey())), false,
                call -> { RegistrationCodeInput in = input(call, RegistrationCodeInput.class);
                    return registration.createCode(in.maxUses(), in.expiresAt()); }));
        tools.add(adminWrite("yingge_admin_registration_code_disable", "停用注册码", "停用注册码，需要人工确认。",
                dangerousIdSchema("registrationCodeId"), true, call -> {
                    registration.disableCode(parseUuid(call.arguments(), "registrationCodeId")); return done();
                }));
        tools.add(adminRead("yingge_admin_audit", "查询审计", "管理员分页读取脱敏审计记录。",
                McpToolSchemas.forRecord(PageInput.class, Map.of()), call -> {
                    PageInput in = input(call, PageInput.class); return auditQuery.list(number(in.page(), 0), number(in.size(), 50));
                }));
        tools.add(adminRead("yingge_admin_ai_settings_get", "读取 AI 配置", "管理员读取服务开关、Provider、模型和密钥是否已配置；不返回密钥。",
                McpToolSchemas.empty(), call -> aiSettings.adminSettings()));
        tools.add(adminWrite("yingge_admin_ai_settings_update", "更新 AI 非密钥配置",
                "管理员更新 AI 服务开关、Provider 地址和模型；MCP 永不接受或返回 API 密钥，需要人工确认。",
                McpToolSchemas.forRecord(SafeAiSettingsInput.class,
                        Map.of("idempotencyKey", idempotencyKey(), "approvalId", approvalId())), true, call -> {
                    SafeAiSettingsInput in = input(call, SafeAiSettingsInput.class);
                    return aiSettings.update(new AssistantAdminDtos.UpdateRequest(
                            in.enabled(), in.baseUrl(), in.model(), null, false, in.expectedVersion()));
                }));
        tools.add(adminRead("yingge_admin_performance_rules", "读取绩效规则版本", "管理员读取全部绩效规则版本。",
                McpToolSchemas.empty(), call -> performance.rules()));
        tools.add(adminWrite("yingge_admin_performance_rule_create", "创建绩效规则版本", "管理员创建未来生效的绩效规则版本。",
                bodySchema(PerformanceDtos.CreateRuleRequest.class, false), false,
                call -> performance.createRule(body(call, PerformanceDtos.CreateRuleRequest.class))));
        tools.add(adminRead("yingge_admin_performance_participants", "读取绩效参与人员", "管理员读取带生效周期的参与人员。",
                McpToolSchemas.empty(), call -> performance.participants()));
        tools.add(adminWrite("yingge_admin_performance_participant_add", "添加绩效参与人员", "管理员添加成员的绩效参与周期。",
                bodySchema(PerformanceDtos.ParticipantRequest.class, false), false,
                call -> performance.addParticipant(body(call, PerformanceDtos.ParticipantRequest.class))));
        tools.add(adminWrite("yingge_admin_performance_participant_update", "更新绩效参与周期", "管理员结束或调整参与周期。",
                idBodySchema("participantId", PerformanceDtos.ParticipantUpdateRequest.class, false), false,
                call -> performance.updateParticipant(parseUuid(call.arguments(), "participantId"),
                        body(call, PerformanceDtos.ParticipantUpdateRequest.class))));
        tools.add(adminWrite("yingge_admin_performance_claim_review", "审核交流绩效", "管理员批准、驳回或重新认定交流类型。",
                idBodySchema("claimId", PerformanceDtos.ReviewClaimRequest.class, false), false,
                call -> performance.reviewClaim(parseUuid(call.arguments(), "claimId"),
                        body(call, PerformanceDtos.ReviewClaimRequest.class))));
        tools.add(adminWrite("yingge_admin_performance_activity_review", "审核活动立项", "管理员批准或驳回活动提案。",
                idBodySchema("activityId", PerformanceDtos.ReviewActivityRequest.class, false), false,
                call -> performance.reviewActivity(parseUuid(call.arguments(), "activityId"),
                        body(call, PerformanceDtos.ReviewActivityRequest.class))));
        tools.add(adminWrite("yingge_admin_performance_activity_allocate", "核定并分配活动分", "管理员核定活动人数档次并按 0.5 分单位完成分配。",
                idBodySchema("activityId", PerformanceDtos.AllocateActivityRequest.class, false), false,
                call -> performance.allocateActivity(parseUuid(call.arguments(), "activityId"),
                        body(call, PerformanceDtos.AllocateActivityRequest.class))));
        tools.add(adminWrite("yingge_admin_performance_manual_entry", "录入绩效人工分", "管理员追加基础后勤分或关联原流水的纠错分。",
                bodySchema(PerformanceDtos.ManualEntryRequest.class, false), false,
                call -> performance.createManualEntry(body(call, PerformanceDtos.ManualEntryRequest.class))));
        tools.add(adminRead("yingge_admin_performance_day", "读取绩效结算日", "管理员读取指定日期状态、待审数量和成员分数。",
                McpToolSchemas.fields(Map.of("date", string()), "date"),
                call -> performance.day(LocalDate.parse(text(call.arguments(), "date")))));
        tools.add(adminWrite("yingge_admin_performance_settle", "结算每日绩效", "管理员在待审核项目全部处理后锁定指定日期。",
                McpToolSchemas.forRecord(PerformanceSettleInput.class, Map.of("idempotencyKey", idempotencyKey())), false,
                call -> { PerformanceSettleInput in = input(call, PerformanceSettleInput.class);
                    return performance.settle(in.date(), in.expectedVersion()); }));
        tools.add(adminWrite("yingge_admin_performance_reopen", "重开已结算绩效日", "保留旧结算修订并重开日期，需要人工确认且必须填写原因。",
                McpToolSchemas.forRecord(PerformanceReopenInput.class,
                        Map.of("idempotencyKey", idempotencyKey(), "approvalId", approvalId())), true,
                call -> { PerformanceReopenInput in = input(call, PerformanceReopenInput.class);
                    return performance.reopen(in.date(), validated(in.request())); }));
        return List.copyOf(tools);
    }

    public List<Map<String, Object>> catalog() {
        return specifications().stream().map(spec -> Map.<String, Object>of(
                "name", spec.tool().name(), "title", spec.tool().title(),
                "description", spec.tool().description(),
                "requiredScope", spec.tool().meta().get("requiredScope"))).toList();
    }

    public List<McpServerFeatures.SyncToolSpecification> visibleSpecifications(McpTransportContext context) {
        Object rawToken = context.get(McpAuthenticationAttributes.TOKEN);
        Object rawPrincipal = context.get(McpAuthenticationAttributes.PRINCIPAL);
        if (!(rawToken instanceof com.yingge.cloud.mcp.oauth.McpOAuthService.TokenAuthentication token)
                || !(rawPrincipal instanceof com.yingge.cloud.auth.infrastructure.CloudPrincipal principal)) {
            return List.of();
        }
        var actor = principal.actor();
        Set<String> adminOnly = Set.of(
                "yingge_task_matrix", "yingge_task_create", "yingge_task_close", "yingge_task_delete",
                "yingge_content_direction_create", "yingge_content_direction_update", "yingge_dictionary_create");
        Set<String> reviewerOnly = Set.of(
                "yingge_customer_lead_review", "yingge_task_assignments", "yingge_task_review",
                "yingge_content_review");
        Set<String> publisherOnly = Set.of(
                "yingge_announcement_create", "yingge_announcement_update", "yingge_announcement_archive");
        Set<String> contributorOnly = Set.of(
                "yingge_performance_claim_create", "yingge_performance_claim_submit",
                "yingge_performance_activity_create", "yingge_performance_activity_complete");
        return specifications().stream().filter(spec -> {
            String name = spec.tool().name();
            String scope = String.valueOf(spec.tool().meta().get("requiredScope"));
            if (!scopeGranted(token.scopes(), scope)) return false;
            if (name.startsWith("yingge_admin_") || adminOnly.contains(name)) return actor.isAdmin();
            if (reviewerOnly.contains(name)) return actor.canReviewTeamOperations();
            if (publisherOnly.contains(name)) return actor.canPublishAnnouncements();
            if (contributorOnly.contains(name)) return actor.roles().stream().anyMatch(role ->
                    role == com.yingge.cloud.permission.domain.RoleCode.ORG_ADMIN
                            || role == com.yingge.cloud.permission.domain.RoleCode.TEAM_MANAGER
                            || role == com.yingge.cloud.permission.domain.RoleCode.MEMBER);
            return true;
        }).toList();
    }

    private McpServerFeatures.SyncToolSpecification read(String name, String title, String description,
                                                          McpSchema.JsonSchema schema, Operation operation) {
        return specification(name, title, description, READ, schema, true, false, false, operation);
    }
    private McpServerFeatures.SyncToolSpecification write(String name, String title, String description,
                                                           McpSchema.JsonSchema schema, boolean dangerous,
                                                           Operation operation) {
        return specification(name, title, description, WRITE, schema, false, true, dangerous, operation);
    }
    private McpServerFeatures.SyncToolSpecification aiRead(String name, String title, String description,
                                                            McpSchema.JsonSchema schema, Operation operation) {
        return specification(name, title, description, AI, schema, true, false, false, operation);
    }
    private McpServerFeatures.SyncToolSpecification ai(String name, String title, String description,
                                                        McpSchema.JsonSchema schema, Operation operation) {
        return specification(name, title, description, AI, schema, false, false, false, operation);
    }
    private McpServerFeatures.SyncToolSpecification adminRead(String name, String title, String description,
                                                               McpSchema.JsonSchema schema, Operation operation) {
        return specification(name, title, description, ADMIN_READ, schema, true, false, false, operation);
    }
    private McpServerFeatures.SyncToolSpecification adminWrite(String name, String title, String description,
                                                                McpSchema.JsonSchema schema, boolean dangerous,
                                                                Operation operation) {
        return specification(name, title, description, ADMIN, schema, false, true, dangerous, operation);
    }

    private McpServerFeatures.SyncToolSpecification specification(
            String name, String title, String description, String scope, McpSchema.JsonSchema schema,
            boolean readOnly, boolean idempotent, boolean dangerous, Operation operation) {
        McpSchema.Tool tool = McpSchema.Tool.builder().name(name).title(title).description(description)
                .inputSchema(schema).outputSchema(commonOutputSchema())
                .annotations(new McpSchema.ToolAnnotations(title, readOnly, dangerous, idempotent, false, false))
                .meta(Map.of("requiredScope", scope,
                        "roleRestricted", scope.equals(ADMIN) || scope.equals(ADMIN_READ))).build();
        return McpServerFeatures.SyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
            ToolCall call = new ToolCall(exchange, request.arguments() == null ? Map.of() : request.arguments(), schema);
            String traceId = execution.traceId(exchange);
            try {
                return execution.call(exchange, scope, () -> invoke(name, dangerous, idempotent, operation, call, traceId));
            } catch (ApiException exception) {
                return results.error(exception.code(), safeMessage(exception), traceId, null);
            } catch (Exception exception) {
                log.error("MCP call failed tool={} traceId={}", name, traceId, exception);
                return results.error("MCP_INTERNAL_ERROR", "工具执行失败，请使用 traceId 联系管理员", traceId, null);
            }
        }).build();
    }

    private McpSchema.CallToolResult invoke(String name, boolean dangerous, boolean idempotent,
                                             Operation operation, ToolCall call, String traceId) {
        long started = System.nanoTime();
        try {
            rejectUnknownArguments(call);
            var token = execution.token(call.exchange());
            String approvalSummary = approvalSummary(name, call.arguments());
            if (dangerous && blank(call.arguments().get("approvalId"))) {
                var approval = approvals.requireOrCreate(token, name, call.arguments(), approvalSummary);
                auditCall(name, token.clientId(), "APPROVAL_REQUIRED", started);
                return results.approval(approval, traceId);
            }
            Function<Void, Object> perform = ignored -> {
                if (dangerous) approvals.requireOrCreate(token, name, call.arguments(), approvalSummary);
                return operation.apply(call);
            };
            Object data = idempotent ? idempotency.execute(token, name, call.arguments(), () -> perform.apply(null))
                    : perform.apply(null);
            auditCall(name, token.clientId(), "SUCCESS", started);
            return results.success(data, traceId);
        } catch (ConstraintViolationException exception) {
            auditCall(name, execution.token(call.exchange()).clientId(), "VALIDATION_FAILED", started);
            List<Map<String, String>> fields = exception.getConstraintViolations().stream().map(violation ->
                    Map.of("field", violation.getPropertyPath().toString(), "message", violation.getMessage())).toList();
            return results.error("VALIDATION_FAILED", "工具参数校验失败", traceId, fields);
        } catch (IllegalArgumentException exception) {
            auditCall(name, execution.token(call.exchange()).clientId(), "INVALID_ARGUMENT", started);
            return results.error("INVALID_ARGUMENT", "工具参数格式无效", traceId, null);
        } catch (ApiException exception) {
            auditCall(name, execution.token(call.exchange()).clientId(), exception.code(), started);
            return results.error(exception.code(), safeMessage(exception), traceId, null);
        } catch (Exception exception) {
            auditCall(name, execution.token(call.exchange()).clientId(), "ERROR", started);
            log.error("MCP tool exception tool={} traceId={}", name, traceId, exception);
            return results.error("MCP_INTERNAL_ERROR", "工具执行失败，请使用 traceId 联系管理员", traceId, null);
        }
    }

    private void auditCall(String tool, String clientId, String outcome, long started) {
        try {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            audit.record(actors.require(), "MCP_TOOL_CALL", "MCP_TOOL", tool, outcome,
                    Map.of("tool", tool, "clientId", clientId, "durationMs", durationMs));
        } catch (Exception exception) {
            log.warn("Unable to persist MCP call audit for {}", tool, exception);
        }
    }

    private void rejectUnknownArguments(ToolCall call) {
        Set<String> allowed = call.schema().properties().keySet();
        List<String> unknown = call.arguments().keySet().stream().filter(key -> !allowed.contains(key)).toList();
        if (!unknown.isEmpty()) throw ApiException.badRequest("UNKNOWN_ARGUMENT", "工具包含未声明参数: " + String.join(", ", unknown));
    }

    private <T> T input(ToolCall call, Class<T> type) {
        return validated(mapper.convertValue(call.arguments(), type));
    }
    private <T> T body(ToolCall call, Class<T> type) {
        Object raw = call.arguments().get("request");
        if (raw == null) throw ApiException.badRequest("REQUEST_REQUIRED", "request 不能为空");
        return validated(mapper.convertValue(raw, type));
    }
    private <T> T validated(T value) {
        var violations = validator.validate(value);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        return value;
    }

    private McpSchema.JsonSchema bodySchema(Class<?> body, boolean dangerous) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        fields.put("request", nested(body));
        fields.put("idempotencyKey", idempotencyKey());
        if (dangerous) fields.put("approvalId", approvalId());
        return McpToolSchemas.fields(fields, "request");
    }
    private McpSchema.JsonSchema idBodySchema(String id, Class<?> body, boolean dangerous) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        fields.put(id, uuid());
        fields.put("request", nested(body));
        fields.put("idempotencyKey", idempotencyKey());
        if (dangerous) fields.put("approvalId", approvalId());
        return McpToolSchemas.fields(fields, id, "request");
    }
    private McpSchema.JsonSchema idWriteSchema(String id, boolean dangerous) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        fields.put(id, uuid()); fields.put("idempotencyKey", idempotencyKey());
        if (dangerous) fields.put("approvalId", approvalId());
        return McpToolSchemas.fields(fields, id);
    }
    private McpSchema.JsonSchema idempotentEmptySchema() {
        return McpToolSchemas.fields(Map.of("idempotencyKey", idempotencyKey()));
    }
    private McpSchema.JsonSchema customerCycleBodySchema(Class<?> body, boolean dangerous) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        fields.put("customerId", uuid());
        fields.put("cycleId", uuid());
        fields.put("request", nested(body));
        fields.put("idempotencyKey", idempotencyKey());
        if (dangerous) fields.put("approvalId", approvalId());
        return McpToolSchemas.fields(fields, "customerId", "cycleId", "request");
    }
    private McpSchema.JsonSchema participantChangeConfirmSchema() {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        fields.put("customerId", uuid());
        fields.put("cycleId", uuid());
        fields.put("requestId", uuid());
        fields.put("idempotencyKey", idempotencyKey());
        return McpToolSchemas.fields(fields, "customerId", "cycleId", "requestId");
    }
    private McpSchema.JsonSchema forecastBodySchema(Class<?> body) {
        Map<String, Map<String, Object>> fields = new LinkedHashMap<>();
        fields.put("customerId", uuid());
        fields.put("cycleId", uuid());
        fields.put("forecastId", uuid());
        fields.put("request", nested(body));
        fields.put("idempotencyKey", idempotencyKey());
        return McpToolSchemas.fields(fields, "customerId", "cycleId", "forecastId", "request");
    }
    private McpSchema.JsonSchema dangerousIdSchema(String id) { return idWriteSchema(id, true); }
    private Map<String, Object> nested(Class<?> type) {
        McpSchema.JsonSchema schema = McpToolSchemas.forRecord(type, Map.of());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "object"); value.put("properties", schema.properties());
        value.put("required", schema.required()); value.put("additionalProperties", false);
        return value;
    }
    private Map<String, Object> commonOutputSchema() {
        return Map.of("type", "object", "required", List.of("ok", "traceId"),
                "properties", Map.of("ok", bool(), "data", Map.of(), "traceId", string(),
                        "warnings", Map.of("type", "array", "items", string()), "error", Map.of("type", "object")));
    }
    private Map<String, Object> done() { return Map.of("completed", true); }
    private boolean booleanValue(Object value) {
        if (value instanceof Boolean flag) return flag;
        return value != null && Boolean.parseBoolean(value.toString());
    }
    private int number(Integer value, int fallback) { return value == null ? fallback : value; }
    private UUID parseUuid(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null) throw ApiException.badRequest("ID_REQUIRED", name + " 不能为空");
        try { return UUID.fromString(value.toString()); }
        catch (IllegalArgumentException exception) { throw ApiException.badRequest("INVALID_ID", name + " 格式无效"); }
    }
    private String text(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (value == null || value.toString().isBlank()) throw ApiException.badRequest("VALUE_REQUIRED", name + " 不能为空");
        return value.toString();
    }
    private boolean blank(Object value) { return value == null || value.toString().isBlank(); }
    private boolean scopeGranted(Set<String> scopes, String requiredScope) {
        return scopes.contains(requiredScope)
                || (ADMIN_READ.equals(requiredScope) && scopes.contains(ADMIN));
    }
    private String approvalSummary(String tool, Map<String, Object> arguments) {
        return switch (tool) {
            case "yingge_customer_archive" -> "归档客户 " + arguments.get("customerId") + " 并取消其活跃提醒";
            case "yingge_customer_transfer" -> "转接客户 " + arguments.get("customerId") + " 的所有权和待办提醒";
            case "yingge_task_close" -> "关闭工作任务 " + arguments.get("taskId");
            case "yingge_task_delete" -> "删除尚无进度的工作任务 " + arguments.get("taskId");
            case "yingge_announcement_archive" -> "归档公告 " + arguments.get("announcementId");
            case "yingge_admin_member_update" -> "修改成员角色、状态或团队 " + arguments.get("membershipId");
            case "yingge_admin_member_delete" -> "转移客户与提醒后删除成员 " + arguments.get("membershipId");
            case "yingge_admin_team_delete" -> "删除团队 " + arguments.get("teamId") + "，保留成员、客户和历史";
            case "yingge_admin_registration_code_disable" -> "停用注册码 " + arguments.get("registrationCodeId");
            case "yingge_admin_ai_settings_update" -> "更新组织 AI 服务开关、Provider 地址和模型（不涉及 API 密钥）";
            case "yingge_admin_performance_reopen" -> "重开绩效结算日 " + arguments.get("date") + "，重新处理后需要再次结算";
            default -> "确认执行 " + tool;
        };
    }
    private String safeMessage(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank() ? "请求失败" : exception.getMessage();
    }

    private record ToolCall(McpSyncServerExchange exchange, Map<String, Object> arguments,
                            McpSchema.JsonSchema schema) {}
    @FunctionalInterface private interface Operation { Object apply(ToolCall call); }

    public record CustomerSearchInput(String search, String status, String intention, String verification,
                                      String grade, String school, List<UUID> tagIds, Boolean includeArchived,
                                      UUID teamId, UUID membershipId, UUID ownerMembershipId,
                                      Integer page, Integer size, String sort) {}
    public record NotificationInboxInput(Boolean unreadOnly, Integer size) {}
    public record PoolListInput(String type) {}
    public record TaskReviewInput(@NotNull UUID taskId, @NotNull UUID assignmentId,
                                  @NotNull @Valid TaskDtos.ReviewRequest request, String idempotencyKey) {}
    public record ContentCircleInput(Integer page, Integer size, String search, UUID membershipId,
                                     ContentPlatform platform, ContentReviewStatus reviewStatus, String sort) {}
    public record DailyReportInput(@NotNull LocalDate date, @NotNull @Valid DailyReportDtos.UpsertRequest request,
                                   String idempotencyKey) {}
    public record TeamDataInput(UUID teamId, UUID membershipId, String search, String grade) {}
    public record LeadershipDashboardInput(UUID teamId, UUID membershipId, Integer days) {}
    public record PerformanceRangeInput(@NotNull LocalDate from, @NotNull LocalDate to, UUID teamId) {}
    public record PerformanceListInput(String status, UUID membershipId, LocalDate date) {}
    public record PerformanceSettleInput(@NotNull LocalDate date, long expectedVersion, String idempotencyKey) {}
    public record PerformanceReopenInput(@NotNull LocalDate date,
                                         @NotNull @Valid PerformanceDtos.ReopenDayRequest request,
                                         String idempotencyKey, UUID approvalId) {}
    public record DeleteMemberInput(@NotNull UUID membershipId, @NotNull UUID transferCustomerToMembershipId,
                                    String idempotencyKey, UUID approvalId) {}
    public record TeamAssignInput(@NotNull UUID teamId, @NotNull UUID membershipId, boolean manager,
                                  String idempotencyKey) {}
    public record TeamLeaderInput(@NotNull UUID teamId, @NotNull UUID membershipId, String idempotencyKey) {}
    public record RegistrationCodeInput(Integer maxUses, java.time.Instant expiresAt, String idempotencyKey) {}
    public record PageInput(Integer page, Integer size) {}
    public record SafeAiSettingsInput(boolean enabled,
                                      @NotBlank @Size(max = 500) String baseUrl,
                                      @NotBlank @Size(max = 200) String model,
                                      Long expectedVersion, String idempotencyKey, UUID approvalId) {}
}
