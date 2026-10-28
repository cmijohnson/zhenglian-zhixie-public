package com.yingge.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.intelligence.application.CustomerIntelligenceQueue;
import com.yingge.cloud.mobile.application.MobilePushOutboxService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ApplicationSecurityIntegrationTest {
    private static final String ADMIN_PASSWORD = "Integration-Test-Password-2026";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("yingge_app_test")
            .withUsername("yingge_test")
            .withPassword("test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.bootstrap.admin-username", () -> "admin-test");
        registry.add("app.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("app.bootstrap.admin-display-name", () -> "集成测试管理员");
        registry.add("app.content.campaign.start-date", () -> "2000-01-01");
        registry.add("app.content.campaign.end-date", () -> "2099-12-31");
        registry.add("app.customer-intelligence.worker-initial-delay", () -> "3600000");
        registry.add("app.ai-admin.master-key", () -> java.util.Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    CustomerIntelligenceQueue intelligenceQueue;

    @Autowired
    MobilePushOutboxService mobilePushOutbox;

    @Test
    void malformedMobileDeviceHeaderReturnsStructuredBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/mobile/auth/login")
                        .header("X-Yingge-Device-Id", "not-a-uuid")
                        .header("X-Yingge-Platform", "ANDROID")
                        .header("X-Yingge-App-Version", "2026.08.23-test")
                        .header("X-Yingge-Version-Code", "4")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "admin-test",
                                "password", "wrong-password",
                                "deviceName", "Malformed device integration"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("X-Yingge-Device-Id"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void mobileBearerIsDeviceBoundBypassesCsrfAndDealApprovalPreservesCustomerWorkflow() throws Exception {
        Cookie adminCookie = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        mockMvc.perform(post("/api/v1/customers").cookie(adminCookie)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "fullName", "无CSRF客户" + suffix, "qqId", "csrf-" + suffix,
                                "visibility", "PRIVATE"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/mobile/auth/registration-options")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/mobile/auth/register")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        UUID deviceId = UUID.randomUUID();
        var login = mockMvc.perform(mobile(post("/api/v1/mobile/auth/login"), deviceId, null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "admin-test", "password", ADMIN_PASSWORD,
                                "deviceName", "Android integration"))))
                .andExpect(status().isOk()).andReturn();
        var loginBody = objectMapper.readTree(login.getResponse().getContentAsString());
        String access = loginBody.get("accessToken").asText();
        String refresh = loginBody.get("refreshToken").asText();

        var rotated = mockMvc.perform(mobile(post("/api/v1/mobile/auth/refresh"), deviceId, null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", refresh))))
                .andExpect(status().isOk()).andReturn();
        access = objectMapper.readTree(rotated.getResponse().getContentAsString()).get("accessToken").asText();

        var created = mockMvc.perform(mobile(post("/api/v1/customers"), deviceId, access)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "fullName", "移动成单客户" + suffix, "qqId", "mobile-" + suffix,
                                "visibility", "PRIVATE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dealState").value("NONE"))
                .andExpect(jsonPath("$.canRequestDeal").value(true))
                .andReturn();
        String customerId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText();

        var before = jdbc.queryForMap("""
                select current_status,version,owner_membership_id,team_id from customers where id=?::uuid
                """, customerId);
        Long collaboratorCount = jdbc.queryForObject("""
                select count(*) from customer_access_grants where customer_id=?::uuid
                """, Long.class, customerId);
        Long reminderCount = jdbc.queryForObject("select count(*) from reminders where customer_id=?::uuid",
                Long.class, customerId);

        var requested = mockMvc.perform(mobile(post("/api/v1/customers/{id}/deal-requests", customerId),
                        deviceId, access).header("Idempotency-Key", "mobile-deal-" + suffix + "-00000000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        var requestBody = objectMapper.readTree(requested.getResponse().getContentAsString());
        String requestId = requestBody.get("id").asText();
        long requestVersion = requestBody.get("version").asLong();

        var secondCreated = mockMvc.perform(mobile(post("/api/v1/customers"), deviceId, access)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "fullName", "移动幂等隔离客户" + suffix,
                                "qqId", "mobile-idempotency-" + suffix,
                                "visibility", "PRIVATE"))))
                .andExpect(status().isCreated()).andReturn();
        String secondCustomerId = objectMapper.readTree(secondCreated.getResponse().getContentAsString())
                .get("id").asText();
        var secondRequested = mockMvc.perform(mobile(post("/api/v1/customers/{id}/deal-requests", secondCustomerId),
                        deviceId, access).header("Idempotency-Key", "mobile-deal-" + suffix + "-00000000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(secondCustomerId))
                .andReturn();
        assertThat(objectMapper.readTree(secondRequested.getResponse().getContentAsString()).get("id").asText())
                .isNotEqualTo(requestId);

        mockMvc.perform(post("/api/v1/admin/deal-requests/{id}/approve", requestId)
                        .cookie(adminCookie).with(csrf()).header("If-Match-Version", requestVersion))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(mobile(get("/api/v1/customers/{id}", customerId), deviceId, access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dealState").value("WON"))
                .andExpect(jsonPath("$.canRequestDeal").value(false));

        var after = jdbc.queryForMap("""
                select current_status,version,owner_membership_id,team_id from customers where id=?::uuid
                """, customerId);
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from customer_access_grants where customer_id=?::uuid",
                Long.class, customerId)).isEqualTo(collaboratorCount);
        assertThat(jdbc.queryForObject("select count(*) from reminders where customer_id=?::uuid",
                Long.class, customerId)).isEqualTo(reminderCount);

        mockMvc.perform(mobile(get("/api/v1/auth/me"), UUID.randomUUID(), access))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOBILE_DEVICE_BINDING_MISMATCH"));

        var ticketResult = mockMvc.perform(mobile(post("/api/v1/mobile/web-sessions"), deviceId, access)
                        .contentType("application/json").content("{\"target\":\"QQ_WORKBENCH\"}"))
                .andExpect(status().isCreated()).andReturn();
        String ticket = objectMapper.readTree(ticketResult.getResponse().getContentAsString())
                .get("ticket").asText();
        var exchanged = mockMvc.perform(post("/api/v1/mobile/web-sessions/exchange").param("ticket", ticket)
                        .contentType("application/x-www-form-urlencoded"))
                .andExpect(status().isSeeOther()).andReturn();
        Cookie webCookie = exchanged.getResponse().getCookie("YINGGE_SESSION");
        assertThat(webCookie).isNotNull();
        mockMvc.perform(get("/api/v1/qq-connections/me").cookie(webCookie))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/admin/teams").cookie(webCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MOBILE_WEB_SESSION_SCOPE_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/customers").cookie(webCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/oauth2/authorize").cookie(webCookie))
                .andExpect(status().isForbidden());

        UUID organizationId = UUID.fromString(loginBody.get("actor").get("organizationId").asText());
        UUID membershipId = UUID.fromString(loginBody.get("actor").get("membershipId").asText());
        mobilePushOutbox.enqueue(organizationId, membershipId, "TASK_ASSIGNED", "/tasks",
                "integration-mobile-push-" + suffix);
        String payload = jdbc.queryForObject("""
                select payload::text from mobile_push_outbox
                where organization_id=? and recipient_membership_id=? and dedupe_key=?
                """, String.class, organizationId, membershipId, "integration-mobile-push-" + suffix);
        assertThat(payload).contains("TASK_ASSIGNED", "\"count\": 1", "\"deeplink\": \"/tasks\"")
                .doesNotContain("移动成单客户", "customerName", "body");
        mobilePushOutbox.enqueue(organizationId, membershipId, "TASK_ASSIGNED", "/tasks",
                "integration-mobile-push-" + suffix);
        assertThat(jdbc.queryForObject("""
                select count(*) from mobile_push_outbox where organization_id=? and dedupe_key=?
                """, Long.class, organizationId, "integration-mobile-push-" + suffix)).isEqualTo(1L);
    }

    @Test
    void mobileWebTicketRotatesSessionEnforcesMethodScopeAndDiesWithSourceDevice() throws Exception {
        UUID deviceId = UUID.randomUUID();
        var login = mockMvc.perform(mobile(post("/api/v1/mobile/auth/login"), deviceId, null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "admin-test", "password", ADMIN_PASSWORD,
                                "deviceName", "WebView isolation integration"))))
                .andExpect(status().isOk()).andReturn();
        String access = objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
        UUID mobileSessionId = jdbc.queryForObject("""
                select id from mobile_device_sessions where installation_id=? and status='ACTIVE'
                """, UUID.class, deviceId.toString());

        var ticketResult = mockMvc.perform(mobile(post("/api/v1/mobile/web-sessions"), deviceId, access)
                        .contentType("application/json").content("{\"target\":\"QQ_WORKBENCH\"}"))
                .andExpect(status().isCreated()).andReturn();
        String ticket = objectMapper.readTree(ticketResult.getResponse().getContentAsString())
                .get("ticket").asText();

        Cookie attackerChosenSession = login();
        var exchangeResult = mockMvc.perform(post("/api/v1/mobile/web-sessions/exchange").param("ticket", ticket)
                        .contentType("application/x-www-form-urlencoded").cookie(attackerChosenSession))
                .andExpect(status().isSeeOther()).andReturn();
        Cookie webCookie = exchangeResult.getResponse().getCookie("YINGGE_SESSION");
        assertThat(webCookie).isNotNull();
        assertThat(webCookie.getValue()).isNotEqualTo(attackerChosenSession.getValue());

        mockMvc.perform(delete("/api/v1/qq-workbench/bootstrap").cookie(webCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MOBILE_WEB_SESSION_SCOPE_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/qq-connections/me").cookie(webCookie))
                .andExpect(status().isNoContent());

        mockMvc.perform(mobile(delete("/api/v1/mobile/auth/devices/{deviceId}", mobileSessionId),
                        deviceId, access))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/qq-connections/me").cookie(webCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MOBILE_WEB_SOURCE_SESSION_REVOKED"));
    }

    @Test
    void passwordChangeThroughMobileBearerRevokesAccessAndRefreshFamily() throws Exception {
        Cookie adminCookie = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String teamId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("name", "移动改密" + suffix))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String username = "mobile-password-" + suffix;
        createMember(adminCookie, username, "移动改密成员", teamId);
        UUID deviceId = UUID.randomUUID();
        var login = mockMvc.perform(mobile(post("/api/v1/mobile/auth/login"), deviceId, null)
                        .contentType("application/json").content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", username, "password", "1", "deviceName", "iPhone integration"))))
                .andExpect(status().isOk()).andReturn();
        var body = objectMapper.readTree(login.getResponse().getContentAsString());
        String access = body.get("accessToken").asText();
        String refresh = body.get("refreshToken").asText();

        mockMvc.perform(mobile(post("/api/v1/auth/password"), deviceId, access)
                        .contentType("application/json").content(objectMapper.writeValueAsString(java.util.Map.of(
                                "currentPassword", "1", "newPassword", "Changed-Password-2026"))))
                .andExpect(status().isNoContent());
        mockMvc.perform(mobile(get("/api/v1/auth/me"), deviceId, access))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(mobile(post("/api/v1/mobile/auth/refresh"), deviceId, null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refreshReplayAndDeviceMismatchPersistentlyRevokeTheWholeMobileSession() throws Exception {
        UUID deviceId = UUID.randomUUID();
        var login = mockMvc.perform(mobile(post("/api/v1/mobile/auth/login"), deviceId, null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "admin-test", "password", ADMIN_PASSWORD,
                                "deviceName", "Replay integration"))))
                .andExpect(status().isOk()).andReturn();
        var first = objectMapper.readTree(login.getResponse().getContentAsString());
        String firstRefresh = first.get("refreshToken").asText();

        var rotated = mockMvc.perform(mobile(post("/api/v1/mobile/auth/refresh"), deviceId, null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", firstRefresh))))
                .andExpect(status().isOk()).andReturn();
        var second = objectMapper.readTree(rotated.getResponse().getContentAsString());
        String secondAccess = second.get("accessToken").asText();
        String secondRefresh = second.get("refreshToken").asText();
        UUID sessionId = jdbc.queryForObject("""
                select id from mobile_device_sessions where installation_id=?
                """, UUID.class, deviceId.toString());
        jdbc.update("""
                insert into mobile_push_registrations
                (id,session_id,provider,vendor,token_hash,token_ciphertext,enabled)
                values (?,?, 'UNIPUSH','TEST',?, ?,true)
                """, UUID.randomUUID(), sessionId, "0".repeat(64), new byte[]{1, 2, 3});

        mockMvc.perform(mobile(post("/api/v1/mobile/auth/refresh"), deviceId, null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", firstRefresh))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOBILE_REFRESH_REPLAYED"));
        assertThat(jdbc.queryForObject("select status from mobile_device_sessions where id=?",
                String.class, sessionId)).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select enabled from mobile_push_registrations where session_id=?",
                Boolean.class, sessionId)).isFalse();
        mockMvc.perform(mobile(get("/api/v1/auth/me"), deviceId, secondAccess))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(mobile(post("/api/v1/mobile/auth/refresh"), deviceId, null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", secondRefresh))))
                .andExpect(status().isUnauthorized());

        UUID boundDevice = UUID.randomUUID();
        var mismatchLogin = mockMvc.perform(mobile(post("/api/v1/mobile/auth/login"), boundDevice, null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "admin-test", "password", ADMIN_PASSWORD,
                                "deviceName", "Binding integration"))))
                .andExpect(status().isOk()).andReturn();
        String mismatchRefresh = objectMapper.readTree(mismatchLogin.getResponse().getContentAsString())
                .get("refreshToken").asText();
        mockMvc.perform(mobile(post("/api/v1/mobile/auth/refresh"), UUID.randomUUID(), null)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("refreshToken", mismatchRefresh))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOBILE_DEVICE_BINDING_MISMATCH"));
        assertThat(jdbc.queryForObject("""
                select status from mobile_device_sessions where installation_id=?
                """, String.class, boundDevice.toString())).isEqualTo("REVOKED");
    }

    @Test
    void sessionLoginCsrfAndCustomerValidationWorkTogether() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());

        var login = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"username":"admin-test","password":"Integration-Test-Password-2026"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ORG_ADMIN"))
                .andReturn();
        var sessionCookie = login.getResponse().getCookie("YINGGE_SESSION");
        assertThat(sessionCookie).isNotNull();
        assertThat(sessionCookie.getMaxAge()).isEqualTo(30 * 24 * 60 * 60);

        mockMvc.perform(get("/api/v1/auth/me").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("admin-test"));

        mockMvc.perform(post("/api/v1/customers")
                        .cookie(sessionCookie)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"fullName":"缺少联系方式的客户","visibility":"PRIVATE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CONTACT_REQUIRED"));

        mockMvc.perform(post("/api/v1/customers")
                        .cookie(sessionCookie)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"fullName":"来源格式异常","phone":"13800138001","sourceDictionaryId":"legacy-source-转专业","visibility":"PRIVATE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/v1/customers")
                        .cookie(sessionCookie)
                        .with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"fullName":"集成测试客户","phone":"138-0013-8000","visibility":"PRIVATE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fullName").value("集成测试客户"));

        mockMvc.perform(get("/api/v1/dashboard").cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalActive").isNumber())
                .andExpect(jsonPath("$.recentUpdated[0].focusDirections").isArray());
    }

    @Test
    void contentReportsAndTaskDistributionWorkTogether() throws Exception {
        Cookie sessionCookie = login();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

        var directionResult = mockMvc.perform(post("/api/v1/content-directions")
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content("""
                                {"title":"大学新生成长","description":"围绕新生适应与学习规划"}
                                """))
                .andExpect(status().isCreated()).andReturn();
        String directionId = objectMapper.readTree(directionResult.getResponse().getContentAsString()).get("id").asText();

        var postResult = mockMvc.perform(post("/api/v1/content-posts")
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "platform", "XIAOHONGSHU", "publishDate", today.toString(),
                                "directionId", directionId, "topic", "新生入学三件事",
                                "contentUrl", "https://example.com/post/1", "viewCount", 120,
                                "likeCount", 12, "favoriteCount", 5, "originalDeclared", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewStatus").value("SUBMITTED"))
                .andReturn();
        String postId = objectMapper.readTree(postResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/content-posts")
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "platform", "XIAOHONGSHU", "publishDate", today.toString(),
                                "directionId", directionId, "topic", "当日重复内容",
                                "contentUrl", "https://example.com/post/2", "viewCount", 1,
                                "likeCount", 0, "favoriteCount", 0, "originalDeclared", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DAILY_PLATFORM_LIMIT"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/content-posts/{id}/review", postId)
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"VALID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("VALID"));

        var leadResult = mockMvc.perform(post("/api/v1/customers")
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content("""
                                {"fullName":"26级有效线索","phone":"13900139000","grade":"26级","visibility":"PRIVATE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.leadReviewStatus").value("PENDING"))
                .andReturn();
        String leadId = objectMapper.readTree(leadResult.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/customers/{id}/lead-review", leadId)
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"VALID\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leadReviewStatus").value("VALID"))
                .andExpect(jsonPath("$.leadReviewerDisplayName").value("集成测试管理员"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                                "/api/v1/daily-reports/{date}", today)
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content("""
                                {"deepCommunicationCount":3,"todayIssues":"需要更多内容素材","tomorrowPlan":"完成回访并发布视频"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grade26NewValidLeads").value(1))
                .andExpect(jsonPath("$.deepCommunicationCount").value(3));

        Instant start = Instant.now().minusSeconds(60);
        Instant due = Instant.now().plusSeconds(86_400);
        var taskResult = mockMvc.perform(post("/api/v1/tasks")
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "title", "自主领取素材整理", "description", "整理一份可复用内容素材",
                                "distributionType", "SELF_CLAIM", "startAt", start.toString(),
                                "dueAt", due.toString(), "maxClaimants", 2))))
                .andExpect(status().isCreated()).andReturn();
        String taskId = objectMapper.readTree(taskResult.getResponse().getContentAsString()).get("id").asText();
        var assignmentResult = mockMvc.perform(post("/api/v1/tasks/{id}/claim", taskId)
                        .cookie(sessionCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignmentSource").value("CLAIMED"))
                .andReturn();
        String assignmentId = objectMapper.readTree(assignmentResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/tasks/{id}/my-assignment", taskId)
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"SUBMITTED\",\"resultSummary\":\"已完成素材整理\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/tasks/{taskId}/assignments/{assignmentId}", taskId, assignmentId)
                        .cookie(sessionCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void registrationCodeCreatesOnlyMemberWithRealNameAndWeakPassword() throws Exception {
        Cookie adminCookie = login();
        String registrationTeamId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"自助注册团队\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        Instant start = Instant.now().minusSeconds(60);
        Instant due = Instant.now().plusSeconds(86_400);
        var taskResult = mockMvc.perform(post("/api/v1/tasks")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "title", "注册成员自动任务", "description", "验证新成员自动继承强制任务",
                                "distributionType", "MANDATORY", "startAt", start.toString(), "dueAt", due.toString()))))
                .andExpect(status().isCreated()).andReturn();
        String taskId = objectMapper.readTree(taskResult.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(post("/api/v1/admin/registration-codes")
                        .with(csrf()).contentType("application/json").content("{\"maxUses\":1}"))
                .andExpect(status().isUnauthorized());

        var created = mockMvc.perform(post("/api/v1/admin/registration-codes")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"maxUses\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").isString())
                .andExpect(jsonPath("$.maxUses").value(1))
                .andReturn();
        String plainCode = objectMapper.readTree(created.getResponse().getContentAsString()).get("code").asText();

        mockMvc.perform(post("/api/v1/auth/registration-options").with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of("registrationCode", plainCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams[*].id", org.hamcrest.Matchers.hasItem(registrationTeamId)));

        String codeList = mockMvc.perform(get("/api/v1/admin/registration-codes").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codeHint").isString())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(codeList).doesNotContain(plainCode);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "registered-member", "realName", "真实姓名成员",
                                "password", "1", "registrationCode", plainCode, "teamId", registrationTeamId))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "registered-member", "realName", " ",
                                "password", "1", "registrationCode", plainCode, "teamId", registrationTeamId))))
                .andExpect(status().isBadRequest());

        var registered = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "registered-member", "realName", "真实姓名成员",
                                "password", "1", "registrationCode", plainCode, "teamId", registrationTeamId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.realName").value("真实姓名成员"))
                .andExpect(jsonPath("$.role").value("MEMBER"))
                .andExpect(jsonPath("$.teamId").value(registrationTeamId))
                .andExpect(jsonPath("$.teamName").value("自助注册团队"))
                .andReturn();
        String membershipId = objectMapper.readTree(registered.getResponse().getContentAsString())
                .get("membershipId").asText();

        assertThat(jdbc.queryForObject("""
                select count(*) from membership_roles mr join roles r on r.id=mr.role_id
                where mr.membership_id=?::uuid and r.code='MEMBER'
                """, Long.class, membershipId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from work_task_assignments
                where task_id=?::uuid and membership_id=?::uuid and assignment_source='MANDATORY'
                """, Long.class, taskId, membershipId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from team_members where team_id=?::uuid and membership_id=?::uuid
                """, Long.class, registrationTeamId, membershipId)).isEqualTo(1L);

        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", "second-member", "realName", "第二位成员",
                                "password", "x", "registrationCode", plainCode, "teamId", registrationTeamId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRATION_CODE_UNAVAILABLE"));

        Cookie memberCookie = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"registered-member\",\"password\":\"1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("真实姓名成员"))
                .andExpect(jsonPath("$.roles[0]").value("MEMBER"))
                .andExpect(jsonPath("$.passwordChangeRequired").value(false))
                .andReturn().getResponse().getCookie("YINGGE_SESSION");
        mockMvc.perform(get("/api/v1/admin/registration-codes").cookie(memberCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void singleTeamLeaderSeesAllMemberCustomersAndReplacementRevokesScope() throws Exception {
        Cookie adminCookie = login();
        var teamResult = mockMvc.perform(post("/api/v1/admin/teams").cookie(adminCookie).with(csrf())
                        .contentType("application/json").content("{\"name\":\"权限验证团队\"}"))
                .andExpect(status().isCreated()).andReturn();
        String teamId = objectMapper.readTree(teamResult.getResponse().getContentAsString()).get("id").asText();
        String leaderAId = createMember(adminCookie, "leader-scope-a", "团队长甲", teamId);
        String leaderBId = createMember(adminCookie, "leader-scope-b", "团队长乙", teamId);

        mockMvc.perform(put("/api/v1/admin/teams/{id}/leader", teamId).cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content("{\"membershipId\":\"" + leaderAId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaderMembershipId").value(leaderAId))
                .andExpect(jsonPath("$.leaderDisplayName").value("团队长甲"))
                .andExpect(jsonPath("$.memberCount").value(2));

        Cookie leaderA = login("leader-scope-a", "1");
        Cookie leaderB = login("leader-scope-b", "1");
        String customerB = objectMapper.readTree(mockMvc.perform(post("/api/v1/customers")
                        .cookie(leaderB).with(csrf()).contentType("application/json")
                        .content("{\"fullName\":\"乙的私有客户\",\"phone\":\"18800001002\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerDisplayName").value("团队长乙"))
                .andExpect(jsonPath("$.creatorDisplayName").value("团队长乙"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        String customerA = objectMapper.readTree(mockMvc.perform(post("/api/v1/customers")
                        .cookie(leaderA).with(csrf()).contentType("application/json")
                        .content("{\"fullName\":\"甲的私有客户\",\"phone\":\"18800001001\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/customers/{id}/lead-review", customerA)
                        .cookie(leaderB).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"VALID\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/customers/{id}/lead-review", customerB)
                        .cookie(leaderA).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"VALID\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.leadReviewStatus").value("VALID"));
        mockMvc.perform(get("/api/v1/customers/{id}", customerB).cookie(leaderA))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/team-data/overview").param("teamId", teamId)
                        .param("membershipId", leaderBId).cookie(leaderA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.memberCount").value(1))
                .andExpect(jsonPath("$.stats.activeCustomerCount").value(1))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.contentProgress.length()").value(1));
        mockMvc.perform(get("/api/v1/team-data/overview").cookie(leaderB))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/team-data/overview").param("teamId", teamId).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.administrator").value(true));

        mockMvc.perform(put("/api/v1/admin/teams/{id}/leader", teamId).cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content("{\"membershipId\":\"" + leaderBId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaderMembershipId").value(leaderBId))
                .andExpect(jsonPath("$.leaderDisplayName").value("团队长乙"));
        mockMvc.perform(get("/api/v1/customers/{id}", customerB).cookie(leaderA))
                .andExpect(status().isUnauthorized());

        leaderA = login("leader-scope-a", "1");
        leaderB = login("leader-scope-b", "1");
        mockMvc.perform(get("/api/v1/customers/{id}", customerB).cookie(leaderA))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/{id}", customerA).cookie(leaderB))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/customers/{id}/lead-review", customerA)
                        .cookie(leaderB).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"VALID\"}"))
                .andExpect(status().isOk());

        var leaderBMe = objectMapper.readTree(mockMvc.perform(get("/api/v1/auth/me").cookie(leaderB))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(leaderBMe.get("roles").toString()).contains("TEAM_MANAGER");
        assertThat(jdbc.queryForObject(
                "select count(*) from team_members where team_id=?::uuid and is_manager=true",
                Long.class, teamId)).isEqualTo(1L);
    }

    @Test
    void announcementsAreVisibleAndTeamLeadersCanPublishOnlyManageTheirOwn() throws Exception {
        Cookie adminCookie = login();
        String teamId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"公告测试团队\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String memberId = createMember(adminCookie, "announcement-reader", "公告读者", teamId);
        Cookie memberCookie = login("announcement-reader", "1");

        mockMvc.perform(post("/api/v1/announcements").cookie(memberCookie).with(csrf())
                        .contentType("application/json")
                        .content("{\"title\":\"越权公告\",\"content\":\"不应成功\",\"priority\":\"NORMAL\",\"pinned\":false}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/teams/{id}/leader", teamId).cookie(adminCookie).with(csrf())
                        .contentType("application/json").content("{\"membershipId\":\"" + memberId + "\"}"))
                .andExpect(status().isOk());
        Cookie managerCookie = login("announcement-reader", "1");
        String managerAnnouncementId = objectMapper.readTree(mockMvc.perform(post("/api/v1/announcements")
                        .cookie(managerCookie).with(csrf()).contentType("application/json")
                        .content("{\"title\":\"团队长公告\",\"content\":\"团队执行要求\",\"priority\":\"IMPORTANT\",\"pinned\":false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorDisplayName").value("公告读者"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        String announcementId = objectMapper.readTree(mockMvc.perform(post("/api/v1/announcements")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"title\":\"紧急测试公告\",\"content\":\"请全员查看\",\"priority\":\"URGENT\",\"pinned\":true,\"showOnLeadershipScreen\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorDisplayName").value("集成测试管理员"))
                .andExpect(jsonPath("$.priority").value("URGENT"))
                .andExpect(jsonPath("$.showOnLeadershipScreen").value(true))
                .andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/announcements").cookie(memberCookie))
                .andExpect(status().isUnauthorized());
        managerCookie = login("announcement-reader", "1");
        mockMvc.perform(get("/api/v1/announcements").cookie(managerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + announcementId + "')].title")
                        .value(org.hamcrest.Matchers.hasItem("紧急测试公告")));
        mockMvc.perform(get("/api/v1/leadership-dashboard").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayMessages[?(@.id == '" + announcementId + "')].title")
                        .value(org.hamcrest.Matchers.hasItem("紧急测试公告")));
        mockMvc.perform(post("/api/v1/announcements/{id}/archive", announcementId)
                        .cookie(managerCookie).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/announcements/{id}/archive", managerAnnouncementId)
                        .cookie(managerCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/announcements/{id}/archive", announcementId)
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/announcements").cookie(managerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + announcementId + "')]").isEmpty());
        assertThat(memberId).isNotBlank();
    }

    @Test
    void teamLeaderReviewsOnlyOwnTeamTasksAndContent() throws Exception {
        Cookie adminCookie = login();
        String teamA = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"审核A组\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String teamB = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"审核B组\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String managerId = createMember(adminCookie, "review-manager", "审核团队长", teamA);
        String memberAId = createMember(adminCookie, "review-member-a", "审核队员甲", teamA);
        String memberBId = createMember(adminCookie, "review-member-b", "审核队员乙", teamB);
        mockMvc.perform(put("/api/v1/admin/teams/{id}/leader", teamA).cookie(adminCookie).with(csrf())
                        .contentType("application/json").content("{\"membershipId\":\"" + managerId + "\"}"))
                .andExpect(status().isOk());
        Cookie managerCookie = login("review-manager", "1");
        Cookie memberACookie = login("review-member-a", "1");
        Cookie memberBCookie = login("review-member-b", "1");

        Instant start = Instant.now().minusSeconds(60);
        Instant due = Instant.now().plusSeconds(86_400);
        String taskId = objectMapper.readTree(mockMvc.perform(post("/api/v1/tasks")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "title", "团队审核任务", "description", "由团队长验收",
                                "distributionType", "MANDATORY", "startAt", start.toString(), "dueAt", due.toString()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/tasks/{id}/my-assignment", taskId)
                        .cookie(memberACookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"SUBMITTED\",\"resultSummary\":\"A组成果\"}"))
                .andExpect(status().isOk());
        String assignmentA = jdbc.queryForObject("""
                select id::text from work_task_assignments where task_id=?::uuid and membership_id=?::uuid
                """, String.class, taskId, memberAId);
        String assignmentB = jdbc.queryForObject("""
                select id::text from work_task_assignments where task_id=?::uuid and membership_id=?::uuid
                """, String.class, taskId, memberBId);
        mockMvc.perform(get("/api/v1/tasks/{id}/assignments", taskId).cookie(managerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].membershipId", org.hamcrest.Matchers.hasItem(memberAId)))
                .andExpect(jsonPath("$[*].membershipId", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(memberBId))));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/tasks/{taskId}/assignments/{assignmentId}", taskId, assignmentA)
                        .cookie(managerCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/tasks/{taskId}/assignments/{assignmentId}", taskId, assignmentB)
                        .cookie(managerCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isForbidden());

        String directionId = objectMapper.readTree(mockMvc.perform(post("/api/v1/content-directions")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"title\":\"团队审核方向\",\"description\":\"测试内容\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        String postA = submitContent(memberACookie, directionId, today, "A组内容", "a");
        String postB = submitContent(memberBCookie, directionId, today, "B组内容", "b");
        mockMvc.perform(get("/api/v1/content-tasks/overview").cookie(managerCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewer").value(true))
                .andExpect(jsonPath("$.administrator").value(false))
                .andExpect(jsonPath("$.reviewQueue[*].id", org.hamcrest.Matchers.hasItem(postA)))
                .andExpect(jsonPath("$.reviewQueue[*].id", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(postB))));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/content-posts/{id}/review", postA)
                        .cookie(managerCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"VALID\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/content-posts/{id}/review", postB)
                        .cookie(managerCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"VALID\"}"))
                .andExpect(status().isForbidden());
        assertThat(memberBCookie).isNotNull();
    }

    @Test
    void administratorCanEditAndSafelyDeleteMembersAndTeams() throws Exception {
        Cookie adminCookie = login();
        String teamId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"可删除团队\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String sourceId = createMember(adminCookie, "delete-source", "待删除成员", teamId);
        String targetId = createMember(adminCookie, "delete-target", "客户接收人", teamId);
        Cookie sourceCookie = login("delete-source", "1");
        String customerId = objectMapper.readTree(mockMvc.perform(post("/api/v1/customers")
                        .cookie(sourceCookie).with(csrf()).contentType("application/json")
                        .content("{\"fullName\":\"待转移客户\",\"phone\":\"17700001234\",\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                                "/api/v1/admin/members/{id}", sourceId)
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"displayName\":\"已改姓名\",\"role\":\"MEMBER\",\"status\":\"ACTIVE\",\"teamId\":\"" + teamId + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("已改姓名"));

        mockMvc.perform(post("/api/v1/admin/members/{id}/delete", sourceId)
                        .cookie(adminCookie).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("""
                select count(*) from customer_pool_entries
                where customer_id=?::uuid and status='OPEN' and release_reason='MEMBER_DEPARTURE'
                """, Long.class, customerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from memberships where id=?::uuid", String.class, sourceId))
                .isEqualTo("DISABLED");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                                "/api/v1/admin/teams/{id}", teamId)
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select active from teams where id=?::uuid", Boolean.class, teamId)).isFalse();
        assertThat(jdbc.queryForObject("select status from memberships where id=?::uuid", String.class, targetId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from team_members where team_id=?::uuid", Long.class, teamId))
                .isZero();
    }

    @Test
    void customerOwnershipTransferPreservesHistoryAndMovesPendingRemindersWithinRoleScope() throws Exception {
        Cookie adminCookie = login();
        String teamAId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"客户转接甲组\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String teamBId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"客户转接乙组\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String ownerId = createMember(adminCookie, "transfer-owner", "原负责人", teamAId);
        String recipientId = createMember(adminCookie, "transfer-recipient", "同组接收人", teamAId);
        String managerId = createMember(adminCookie, "transfer-manager", "甲组团队长", teamAId);
        String outsiderId = createMember(adminCookie, "transfer-outsider", "乙组接收人", teamBId);
        mockMvc.perform(put("/api/v1/admin/teams/{id}/leader", teamAId)
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"membershipId\":\"" + managerId + "\"}"))
                .andExpect(status().isOk());

        Cookie ownerCookie = login("transfer-owner", "1");
        Cookie managerCookie = login("transfer-manager", "1");
        Cookie recipientCookie = login("transfer-recipient", "1");
        var created = objectMapper.readTree(mockMvc.perform(post("/api/v1/customers")
                        .cookie(ownerCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "fullName", "转接链路客户",
                                "phone", "17700005678",
                                "visibility", "PRIVATE",
                                "nextFollowUpAt", Instant.now().plusSeconds(86_400).toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerMembershipId").value(ownerId))
                .andExpect(jsonPath("$.createdByMembershipId").value(ownerId))
                .andReturn().getResponse().getContentAsString());
        String customerId = created.get("id").asText();
        long version = created.get("version").asLong();

        mockMvc.perform(get("/api/v1/customers/{id}/collaboration", customerId).cookie(ownerCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canTransferOwnership").value(true))
                .andExpect(jsonPath("$.transferCandidates[*].membershipId",
                        org.hamcrest.Matchers.hasItem(recipientId)))
                .andExpect(jsonPath("$.transferCandidates[*].membershipId",
                        org.hamcrest.Matchers.hasItem(outsiderId)));

        var firstTransfer = objectMapper.readTree(mockMvc.perform(post("/api/v1/customers/{id}/transfer", customerId)
                        .cookie(ownerCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "targetMembershipId", recipientId,
                                "expectedVersion", version,
                                "reason", "排班调整"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerMembershipId").value(recipientId))
                .andExpect(jsonPath("$.createdByMembershipId").value(ownerId))
                .andExpect(jsonPath("$.teamId").value(teamAId))
                .andReturn().getResponse().getContentAsString());
        version = firstTransfer.get("version").asLong();
        assertThat(jdbc.queryForObject("""
                select assigned_membership_id::text from reminders
                where customer_id=?::uuid and status='PENDING'
                """, String.class, customerId)).isEqualTo(recipientId);
        mockMvc.perform(get("/api/v1/customers/{id}", customerId).cookie(ownerCookie))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/{id}", customerId).cookie(recipientCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerDisplayName").value("同组接收人"))
                .andExpect(jsonPath("$.creatorDisplayName").value("原负责人"));

        mockMvc.perform(post("/api/v1/customers/{id}/transfer", customerId)
                        .cookie(managerCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "targetMembershipId", ownerId,
                                "expectedVersion", version - 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        var managerTransfer = objectMapper.readTree(mockMvc.perform(post(
                                "/api/v1/customers/{id}/transfer", customerId)
                        .cookie(managerCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "targetMembershipId", ownerId,
                                "expectedVersion", version,
                                "reason", "团队长重新分配"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerMembershipId").value(ownerId))
                .andReturn().getResponse().getContentAsString());
        version = managerTransfer.get("version").asLong();

        mockMvc.perform(post("/api/v1/customers/{id}/transfer", customerId)
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "targetMembershipId", outsiderId,
                                "expectedVersion", version,
                                "reason", "管理员跨团队转接"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerMembershipId").value(outsiderId))
                .andExpect(jsonPath("$.teamId").value(teamBId))
                .andExpect(jsonPath("$.createdByMembershipId").value(ownerId));

        assertThat(jdbc.queryForObject("""
                select assigned_membership_id::text from reminders
                where customer_id=?::uuid and status='PENDING'
                """, String.class, customerId)).isEqualTo(outsiderId);
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_logs
                where action='CUSTOMER_OWNERSHIP_TRANSFERRED' and entity_id=?
                """, Long.class, customerId)).isEqualTo(3L);
        assertThat(jdbc.queryForObject("""
                select details->>'targetOwnerMembershipId' from audit_logs
                where action='CUSTOMER_OWNERSHIP_TRANSFERRED' and entity_id=?
                order by created_at desc limit 1
                """, String.class, customerId)).isEqualTo(outsiderId);
    }

    @Test
    void ungroupedCollaboratorWithEditGrantCanTransferPrivateCustomer() throws Exception {
        Cookie adminCookie = login();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String teamId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"协作者转接组-" + suffix + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        String ownerUsername = "grant-owner-" + suffix;
        String collaboratorUsername = "grant-editor-" + suffix;
        String recipientUsername = "grant-recipient-" + suffix;
        String ownerId = createMember(adminCookie, ownerUsername, "授权原负责人", teamId);
        String recipientId = createMember(adminCookie, recipientUsername, "授权接收人", teamId);
        String collaboratorId = createUngroupedMember(adminCookie, collaboratorUsername, "无团队协作者");
        Cookie ownerCookie = login(ownerUsername, "1");
        Cookie collaboratorCookie = login(collaboratorUsername, "1");

        var created = objectMapper.readTree(mockMvc.perform(post("/api/v1/customers")
                        .cookie(ownerCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "fullName", "无团队协作者转接客户-" + suffix,
                                "phone", "176" + String.format("%08d", Math.floorMod(suffix.hashCode(), 100_000_000)),
                                "visibility", "PRIVATE"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String customerId = created.get("id").asText();

        mockMvc.perform(put("/api/v1/customers/{id}/collaborators", customerId)
                        .cookie(ownerCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "membershipIds", java.util.Set.of(collaboratorId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collaborators[*].membershipId", org.hamcrest.Matchers.hasItem(collaboratorId)));

        mockMvc.perform(get("/api/v1/customers/{id}/collaboration", customerId).cookie(collaboratorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canEditProfile").value(true))
                .andExpect(jsonPath("$.canTransferOwnership").value(true))
                .andExpect(jsonPath("$.transferCandidates[*].membershipId", org.hamcrest.Matchers.hasItem(recipientId)));

        mockMvc.perform(post("/api/v1/customers/{id}/transfer", customerId)
                        .cookie(collaboratorCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "targetMembershipId", recipientId,
                                "expectedVersion", created.get("version").asLong(),
                                "reason", "协作者发起转接"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerMembershipId").value(recipientId))
                .andExpect(jsonPath("$.createdByMembershipId").value(ownerId));
    }

    @Test
    void administratorManagesDynamicAiSettingsWithoutExposingSecrets() throws Exception {
        Cookie adminCookie = login();
        mockMvc.perform(get("/api/v1/admin/assistant-settings").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andExpect(jsonPath("$.version").value(0));

        String secret = "test-provider-key-never-return";
        var saved = mockMvc.perform(put("/api/v1/admin/assistant-settings")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "enabled", true, "baseUrl", "https://ai.example.test/v1",
                                "model", "test-model-v2", "apiKey", secret,
                                "clearApiKey", false, "expectedVersion", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(true))
                .andExpect(jsonPath("$.apiKeySource").value("DATABASE"))
                .andExpect(jsonPath("$.model").value("test-model-v2"))
                .andExpect(jsonPath("$.apiKey").doesNotExist())
                .andReturn();
        assertThat(saved.getResponse().getContentAsString()).doesNotContain(secret);
        String encrypted = jdbc.queryForObject("select encrypted_api_key from assistant_settings", String.class);
        assertThat(encrypted).startsWith("v1:").doesNotContain(secret);
        assertThat(jdbc.queryForObject("select details::text from audit_logs where action='AI_SETTINGS_UPDATED' order by created_at desc limit 1", String.class))
                .doesNotContain(secret);

        mockMvc.perform(put("/api/v1/admin/assistant-settings")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"enabled\":true,\"baseUrl\":\"ftp://invalid.example\",\"model\":\"x\",\"clearApiKey\":false,\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_BASE_URL_INVALID"));
        mockMvc.perform(put("/api/v1/admin/assistant-settings")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"enabled\":false,\"baseUrl\":\"https://ai.example.test/v1\",\"model\":\"test-model-v2\",\"clearApiKey\":false,\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        var disabled = mockMvc.perform(put("/api/v1/admin/assistant-settings")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"enabled\":false,\"baseUrl\":\"https://ai.example.test/v1\",\"model\":\"test-model-v2\",\"clearApiKey\":false,\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)).andReturn();
        mockMvc.perform(get("/api/v1/assistant/availability").cookie(adminCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.provider").value("disabled"));

        String teamId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"AI配置权限团队\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        createMember(adminCookie, "ai-settings-member", "AI配置普通成员", teamId);
        Cookie memberCookie = login("ai-settings-member", "1");
        mockMvc.perform(get("/api/v1/admin/assistant-settings").cookie(memberCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/assistant-settings").cookie(memberCookie).with(csrf())
                        .contentType("application/json").content(disabled.getResponse().getContentAsString()))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/assistant-settings")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"enabled\":true,\"baseUrl\":\"https://api.openai.com/v1\",\"model\":\"gpt-4.1-mini\",\"clearApiKey\":true,\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.apiKeySource").value("NONE"));
    }

    @Test
    void administratorManagesLeadershipScreenSettingsAndDashboardUsesThem() throws Exception {
        Cookie adminCookie = login();
        mockMvc.perform(get("/api/v1/admin/leadership-screen-settings").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.animationsEnabled").value(true))
                .andExpect(jsonPath("$.idleTimeoutMinutes").value(20))
                .andExpect(jsonPath("$.sceneOrder.length()").value(9))
                .andExpect(jsonPath("$.featuredGrade").value("2026"))
                .andExpect(jsonPath("$.enabledEvents.length()").value(10))
                .andExpect(jsonPath("$.version").value(0));

        mockMvc.perform(put("/api/v1/admin/leadership-screen-settings")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("""
                                {
                                  "animationsEnabled": true,
                                  "standbyEnabled": true,
                                  "idleTimeoutMinutes": 30,
                                  "sceneDurationSeconds": 15,
                                  "eventDurationSeconds": 8,
                                  "motionIntensity": "STRONG",
                                  "featuredGrade": "2027",
                                  "sceneOrder": ["culture","time","briefing","trend","champions","funnel","mission","followUpRisk","schoolComparison"],
                                  "enabledScenes": ["culture","time","briefing","trend","champions","funnel","followUpRisk","schoolComparison"],
                                  "enabledEvents": ["zeroBreakthrough","taskMilestone","rankingChange"],
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idleTimeoutMinutes").value(30))
                .andExpect(jsonPath("$.motionIntensity").value("STRONG"))
                .andExpect(jsonPath("$.featuredGrade").value("2027"))
                .andExpect(jsonPath("$.sceneOrder[0]").value("culture"))
                .andExpect(jsonPath("$.enabledEvents.length()").value(3))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(get("/api/v1/leadership-dashboard").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.screenSettings.idleTimeoutMinutes").value(30))
                .andExpect(jsonPath("$.screenSettings.motionIntensity").value("STRONG"))
                .andExpect(jsonPath("$.screenSettings.featuredGrade").value("2027"))
                .andExpect(jsonPath("$.screenSettings.version").value(1))
                .andExpect(jsonPath("$.featuredGrade").value("2027"))
                .andExpect(jsonPath("$.aClassStale.thresholdHours").value(72))
                .andExpect(jsonPath("$.schoolComparison.featuredGrade").value("2027"))
                .andExpect(jsonPath("$.tickerItems.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));

        mockMvc.perform(put("/api/v1/admin/leadership-screen-settings")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("""
                                {
                                  "animationsEnabled": true,
                                  "standbyEnabled": true,
                                  "idleTimeoutMinutes": 20,
                                  "sceneDurationSeconds": 12,
                                  "eventDurationSeconds": 7,
                                  "motionIntensity": "CINEMATIC",
                                  "featuredGrade": "2027",
                                  "sceneOrder": ["time","time","briefing","trend","champions","funnel","mission","followUpRisk","schoolComparison"],
                                  "enabledScenes": ["time"],
                                  "enabledEvents": [],
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("LEADERSHIP_SCENE_ORDER_INVALID"));

        String teamId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"大屏配置权限团队\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        createMember(adminCookie, "screen-settings-member", "大屏普通成员", teamId);
        Cookie memberCookie = login("screen-settings-member", "1");
        mockMvc.perform(get("/api/v1/admin/leadership-screen-settings").cookie(memberCookie))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/leadership-screen-settings")
                        .cookie(memberCookie).with(csrf()).contentType("application/json")
                        .content("""
                                {
                                  "animationsEnabled": true,
                                  "standbyEnabled": true,
                                  "idleTimeoutMinutes": 20,
                                  "sceneDurationSeconds": 12,
                                  "eventDurationSeconds": 7,
                                  "motionIntensity": "CINEMATIC",
                                  "featuredGrade": "2027",
                                  "sceneOrder": ["time","culture","briefing","trend","champions","funnel","mission","followUpRisk","schoolComparison"],
                                  "enabledScenes": ["time"],
                                  "enabledEvents": [],
                                  "expectedVersion": 1
                                }
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void customerIntelligenceQueuesOneVersionedReadOnlyJobAndProtectsAdminSettings() throws Exception {
        Cookie adminCookie = login();
        var created = objectMapper.readTree(mockMvc.perform(post("/api/v1/customers")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("""
                                {
                                  "fullName": "智能分析集成客户",
                                  "phone": "18800008614",
                                  "school": "示例大学",
                                  "major": "计算机科学与技术",
                                  "grade": "2027",
                                  "demandSummary": "希望了解转专业规划",
                                  "visibility": "PRIVATE"
                                }
                                """))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String customerId = created.get("id").asText();

        mockMvc.perform(get("/api/v1/customers/{id}/intelligence", customerId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/customers/{id}/intelligence", customerId).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.processing").value(true))
                .andExpect(jsonPath("$.processingStatus").value("PENDING"))
                .andExpect(jsonPath("$.latest").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/v1/customers/{id}/intelligence/versions", customerId).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(post("/api/v1/customers/{id}/intelligence/reanalyze", customerId)
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.processingStatus").value("PENDING"));
        assertThat(jdbc.queryForObject("""
                select count(*) from customer_analysis_jobs
                where customer_id=?::uuid and status in ('PENDING','RUNNING')
                """, Integer.class, customerId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select demand_summary from customers where id=?::uuid
                """, String.class, customerId)).isEqualTo("希望了解转专业规划");

        String activeJobId = jdbc.queryForObject("""
                select id::text from customer_analysis_jobs
                where customer_id=?::uuid and status='PENDING'
                """, String.class, customerId);
        jdbc.update("""
                update customer_analysis_jobs
                set status='RUNNING', attempts=2, rerun_requested=true, locked_at=now()
                where id=?::uuid
                """, activeJobId);
        intelligenceQueue.succeed(java.util.UUID.fromString(activeJobId));
        assertThat(jdbc.queryForObject("""
                select attempts from customer_analysis_jobs where id=?::uuid
                """, Integer.class, activeJobId)).isZero();
        assertThat(jdbc.queryForObject("""
                select status from customer_analysis_jobs where id=?::uuid
                """, String.class, activeJobId)).isEqualTo("PENDING");

        mockMvc.perform(get("/api/v1/admin/customer-intelligence/settings").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embeddingModel").value("bge-m3"))
                .andExpect(jsonPath("$.nightlyRatePerMinute").value(10))
                .andExpect(jsonPath("$.pendingJobs").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
        mockMvc.perform(put("/api/v1/admin/customer-intelligence/settings")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("""
                                {
                                  "enabled": true,
                                  "autoProfileAnalysis": true,
                                  "autoTimelineAnalysis": true,
                                  "embeddingBaseUrl": "file:///etc/passwd",
                                  "embeddingModel": "bge-m3",
                                  "recentInteractionLimit": 10,
                                  "semanticChunkLimit": 12,
                                  "backfillEnabled": true,
                                  "backfillPaused": false,
                                  "nightlyRatePerMinute": 10,
                                  "expectedVersion": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMBEDDING_BASE_URL_INVALID"));

        mockMvc.perform(get("/api/v1/customers/{id}/intelligence", java.util.UUID.randomUUID())
                        .cookie(adminCookie))
                .andExpect(status().isNotFound());

        String teamId = objectMapper.readTree(mockMvc.perform(post("/api/v1/admin/teams")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"name\":\"智能分析权限团队\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        createMember(adminCookie, "intelligence-member", "智能分析普通成员", teamId);
        Cookie memberCookie = login("intelligence-member", "1");
        mockMvc.perform(get("/api/v1/admin/customer-intelligence/settings").cookie(memberCookie))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/customers/{id}/archive", customerId)
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/customers/{id}/intelligence", customerId).cookie(adminCookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/customers/{id}/intelligence/versions", customerId).cookie(adminCookie))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/customers/{id}/intelligence/reanalyze", customerId)
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ARCHIVED_CUSTOMER_ANALYSIS_FORBIDDEN"));
        mockMvc.perform(post("/api/v1/reminders").cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "customerId", customerId,
                                "remindAt", "2026-09-20T01:00:00Z",
                                "title", "归档客户不得创建提醒"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ARCHIVED_CUSTOMER_REMINDER_FORBIDDEN"));
    }

    private String createMember(Cookie adminCookie, String username, String displayName, String teamId) throws Exception {
        var result = mockMvc.perform(post("/api/v1/admin/members").cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", username, "displayName", displayName, "initialPassword", "1",
                                "role", "MEMBER", "teamId", teamId))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("membershipId").asText();
    }

    private String createUngroupedMember(Cookie adminCookie, String username, String displayName) throws Exception {
        var result = mockMvc.perform(post("/api/v1/admin/members").cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", username, "displayName", displayName,
                                "initialPassword", "1", "role", "MEMBER"))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("membershipId").asText();
    }

    private String submitContent(Cookie cookie, String directionId, LocalDate date,
                                 String topic, String suffix) throws Exception {
        var result = mockMvc.perform(post("/api/v1/content-posts")
                        .cookie(cookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "platform", "DOUYIN", "publishDate", date.toString(), "directionId", directionId,
                                "topic", topic, "contentUrl", "https://example.com/team-review/" + suffix,
                                "viewCount", 10, "likeCount", 1, "favoriteCount", 1,
                                "originalDeclared", true))))
                .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private Cookie login() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf()).contentType("application/json")
                        .content("""
                                {"username":"admin-test","password":"Integration-Test-Password-2026"}
                                """))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("YINGGE_SESSION");
    }

    private Cookie login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("YINGGE_SESSION");
    }

    private <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> T mobile(
            T request, UUID deviceId, String accessToken) {
        request.header("X-Yingge-Device-Id", deviceId)
                .header("X-Yingge-Platform", "ANDROID")
                .header("X-Yingge-App-Version", "2026.08.22-test")
                .header("X-Yingge-Version-Code", "1");
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return request;
    }
}
