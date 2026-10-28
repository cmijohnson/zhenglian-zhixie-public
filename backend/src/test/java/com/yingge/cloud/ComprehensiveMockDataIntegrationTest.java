package com.yingge.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.support.MockDataFactory;
import com.yingge.cloud.followup.application.CustomerFollowUpService;
import jakarta.servlet.http.Cookie;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComprehensiveMockDataIntegrationTest {
    private static final String ADMIN_PASSWORD = "Full-Suite-Admin-Password-2026";
    private static final String MEMBER_PASSWORD = "Full-Suite-Member-Password-2026";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("yingge_full_suite")
            .withUsername("yingge_full_test")
            .withPassword("full-test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.customer-intelligence.worker-initial-delay", () -> "3600000");
        registry.add("app.bootstrap.admin-username", () -> "full-admin");
        registry.add("app.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("app.bootstrap.admin-display-name", () -> "全量测试管理员");
        registry.add("app.content.campaign.start-date", () -> "2000-01-01");
        registry.add("app.content.campaign.end-date", () -> "2099-12-31");
        registry.add("yingge.webhook.master-key", () -> Base64.getEncoder()
                .encodeToString("full-suite-webhook-key-32-byte!!".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired CustomerFollowUpService followUps;

    private static boolean fixtureReady;
    private static Cookie adminCookie;
    private static UUID organizationId;
    private static UUID adminMembershipId;
    private static UUID teamA;
    private static UUID teamB;
    private static MemberAccount managerA;
    private static MemberAccount memberA1;
    private static MemberAccount memberA2;
    private static MemberAccount managerB;
    private static MemberAccount memberB1;
    private static MemberAccount memberB2;
    private static MemberAccount viewerA;
    private static MemberAccount otherOrganizationMember;
    private static MockDataFactory.SeededData scaleData;

    @BeforeEach
    void createOrganizationScenarioAndLargeFixture() throws Exception {
        if (fixtureReady) return;
        adminCookie = login("full-admin", ADMIN_PASSWORD);
        JsonNode me = json(mockMvc.perform(get("/api/v1/auth/me").cookie(adminCookie))
                .andExpect(status().isOk()).andReturn());
        organizationId = UUID.fromString(me.get("organizationId").asText());
        adminMembershipId = UUID.fromString(me.get("membershipId").asText());

        teamA = UUID.fromString(createTeam("华东营销组").get("id").asText());
        teamB = UUID.fromString(createTeam("内容增长组").get("id").asText());
        managerA = createMember("manager-a", "A组主管", "TEAM_MANAGER", teamA);
        memberA1 = createMember("member-a1", "A组成员1", "MEMBER", teamA);
        memberA2 = createMember("member-a2", "A组成员2", "MEMBER", teamA);
        managerB = createMember("manager-b", "B组主管", "TEAM_MANAGER", teamB);
        memberB1 = createMember("member-b1", "B组成员1", "MEMBER", teamB);
        memberB2 = createMember("member-b2", "B组成员2", "MEMBER", teamB);
        viewerA = createMember("viewer-a", "A组只读", "VIEWER", teamA);

        List<MockDataFactory.MemberSeed> contributors = List.of(
                new MockDataFactory.MemberSeed(managerA.membershipId(), teamA),
                new MockDataFactory.MemberSeed(memberA1.membershipId(), teamA),
                new MockDataFactory.MemberSeed(memberA2.membershipId(), teamA),
                new MockDataFactory.MemberSeed(managerB.membershipId(), teamB),
                new MockDataFactory.MemberSeed(memberB1.membershipId(), teamB),
                new MockDataFactory.MemberSeed(memberB2.membershipId(), teamB)
        );
        scaleData = MockDataFactory.seedCustomerScale(jdbc, organizationId, contributors, 600, "规模客户");
        otherOrganizationMember = createOtherOrganizationFixture();
        fixtureReady = true;
    }

    @Test
    @Order(1)
    void authenticationCsrfSessionPasswordAndAdminBoundariesAreEnforced() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        mockMvc.perform(post("/api/v1/tags").cookie(memberA1.cookie())
                        .contentType("application/json")
                        .content("{\"name\":\"no-csrf\",\"category\":\"test\",\"color\":\"#123456\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"member-a1\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mockMvc.perform(get("/api/v1/admin/members").cookie(viewerA.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/members").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8));

        mockMvc.perform(post("/api/v1/admin/members").cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "weak-password-viewer", "displayName", "弱密码用户",
                                "initialPassword", "1", "role", "VIEWER"))))
                .andExpect(status().isCreated());
        assertThat(login("weak-password-viewer", "1")).isNotNull();

        MemberAccount passwordUser = createMember("password-flow", "密码流程用户", "VIEWER", teamA);
        String newPassword = "1";
        mockMvc.perform(post("/api/v1/auth/password").cookie(passwordUser.cookie()).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", MEMBER_PASSWORD, "newPassword", newPassword))))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/auth/logout").cookie(passwordUser.cookie()).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "password-flow", "password", MEMBER_PASSWORD))))
                .andExpect(status().isUnauthorized());
        assertThat(login("password-flow", newPassword)).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from spring_session", Long.class)).isPositive();
    }

    @Test
    @Order(2)
    void sixHundredCustomersAndThousandsOfRelatedRowsSupportPagingDashboardAndTenantIsolation() throws Exception {
        JsonNode firstPage = json(mockMvc.perform(get("/api/v1/customers")
                        .param("size", "100").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(100))
                .andReturn());
        assertThat(firstPage.get("totalElements").asLong()).isGreaterThanOrEqualTo(576);
        assertThat(firstPage.get("totalPages").asInt()).isGreaterThanOrEqualTo(6);

        JsonNode ownerSchoolPage = json(mockMvc.perform(get("/api/v1/customers")
                        .param("school", "示例大学")
                        .param("ownerMembershipId", memberA1.membershipId().toString())
                        .param("size", "100").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].school",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("示例大学"))))
                .andExpect(jsonPath("$.items[*].ownerMembershipId",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(memberA1.membershipId().toString()))))
                .andReturn());
        assertThat(ownerSchoolPage.get("totalElements").asLong()).isPositive();
        mockMvc.perform(get("/api/v1/customers").param("school", "不存在的院校")
                        .param("ownerMembershipId", memberA1.membershipId().toString()).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/customers").param("search", "规模客户-0050")
                        .param("includeArchived", "true").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/dashboard").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalActive").isNumber())
                .andExpect(jsonPath("$.recentUpdated[0].focusDirections").isArray());

        JsonNode reminders = json(mockMvc.perform(get("/api/v1/reminders").cookie(adminCookie))
                .andExpect(status().isOk()).andReturn());
        assertThat(reminders.size()).isGreaterThan(500);
        assertThat(jdbc.queryForObject("select count(*) from interactions where organization_id=?", Long.class, organizationId))
                .isEqualTo(1_200);

        long memberVisible = json(mockMvc.perform(get("/api/v1/customers").param("size", "1").cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andReturn()).get("totalElements").asLong();
        long managerVisible = json(mockMvc.perform(get("/api/v1/customers").param("size", "1").cookie(managerA.cookie()))
                .andExpect(status().isOk()).andReturn()).get("totalElements").asLong();
        assertThat(memberVisible).isBetween(1L, firstPage.get("totalElements").asLong() - 1);
        assertThat(managerVisible).isGreaterThan(memberVisible);

        MockDataFactory.SeededCustomer hidden = scaleData.firstPrivateOwnedBy(managerB.membershipId());
        mockMvc.perform(get("/api/v1/customers/{id}", hidden.id()).cookie(memberA1.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/customers/{id}", hidden.id()).cookie(managerB.cookie()))
                .andExpect(status().isOk());

        JsonNode viewerPage = json(mockMvc.perform(get("/api/v1/customers").cookie(viewerA.cookie()))
                .andExpect(status().isOk()).andReturn());
        assertThat(viewerPage.get("totalElements").asLong()).isZero();

        JsonNode otherTenant = json(mockMvc.perform(get("/api/v1/customers").param("size", "100")
                        .param("includeArchived", "true")
                        .cookie(otherOrganizationMember.cookie()))
                .andExpect(status().isOk()).andReturn());
        assertThat(otherTenant.get("totalElements").asLong()).isEqualTo(40);
        assertThat(otherTenant.get("items").get(0).get("fullName").asText()).startsWith("隔离组织客户");
    }

    @Test
    @Order(3)
    void customerDictionaryTagInteractionReminderDuplicateAndArchiveLifecycleIsTransactional() throws Exception {
        JsonNode initialDictionaries = json(mockMvc.perform(get("/api/v1/dictionaries").cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andReturn());
        List<String> schoolLabels = new ArrayList<>();
        List<String> sourceLabels = new ArrayList<>();
        initialDictionaries.forEach(item -> {
            if ("SCHOOL".equals(item.get("type").asText())) schoolLabels.add(item.get("label").asText());
            if ("SOURCE_CHANNEL".equals(item.get("type").asText())) sourceLabels.add(item.get("label").asText());
        });
        assertThat(schoolLabels).contains("示例大学", "示例科技大学");
        assertThat(sourceLabels).contains("小红书", "抖音", "新生群", "运营商", "朋友推荐", "其他")
                .doesNotContain("活动", "社群", "线上咨询");

        JsonNode school = json(mockMvc.perform(post("/api/v1/dictionaries/schools").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json").content("{\"label\":\"示例大学B\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SCHOOL"))
                .andReturn());
        mockMvc.perform(post("/api/v1/dictionaries/schools").cookie(memberA2.cookie()).with(csrf())
                        .contentType("application/json").content("{\"label\":\"示例大学B\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHOOL_EXISTS"));
        mockMvc.perform(post("/api/v1/dictionaries/schools").cookie(viewerA.cookie()).with(csrf())
                        .contentType("application/json").content("{\"label\":\"无权限大学\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/dictionaries/sources").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json").content("{\"label\":\"校园地推\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("SOURCE_CHANNEL"))
                .andExpect(jsonPath("$.label").value("校园地推"));
        mockMvc.perform(post("/api/v1/dictionaries/sources").cookie(memberA2.cookie()).with(csrf())
                        .contentType("application/json").content("{\"label\":\"校园地推\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_EXISTS"));
        mockMvc.perform(post("/api/v1/dictionaries/sources").cookie(viewerA.cookie()).with(csrf())
                        .contentType("application/json").content("{\"label\":\"无权限来源\"}"))
                .andExpect(status().isForbidden());

        JsonNode dictionary = json(mockMvc.perform(post("/api/v1/dictionaries").cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content("{\"type\":\"SOURCE_CHANNEL\",\"code\":\"FULL_TEST\",\"label\":\"全量测试来源\",\"sortOrder\":10}"))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/dictionaries").cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content("{\"type\":\"SOURCE_CHANNEL\",\"code\":\"FULL_TEST\",\"label\":\"重复\",\"sortOrder\":11}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DICTIONARY_CODE_EXISTS"));

        JsonNode tag = json(mockMvc.perform(post("/api/v1/tags").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"全量高意向\",\"category\":\"意向\",\"color\":\"#FF6600\"}"))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/tags").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"2027\",\"category\":\"年级\",\"color\":\"#2563EB\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GRADE_TAG_NOT_ALLOWED"));
        mockMvc.perform(post("/api/v1/tags").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"name\":\"自定义A级\",\"category\":\"客户质量\",\"color\":\"#16A34A\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOMER_QUALITY_TAG_RESERVED"));
        JsonNode allTags = json(mockMvc.perform(get("/api/v1/tags").cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andReturn());
        List<JsonNode> qualityTags = new ArrayList<>();
        allTags.forEach(item -> {
            if ("客户质量".equals(item.get("category").asText())) qualityTags.add(item);
        });
        assertThat(qualityTags).hasSize(3).allMatch(item -> item.get("system").asBoolean());
        JsonNode qualityA = qualityTags.stream()
                .filter(item -> "A类用户".equals(item.get("name").asText())).findFirst().orElseThrow();
        JsonNode qualityB = qualityTags.stream()
                .filter(item -> "B类用户".equals(item.get("name").asText())).findFirst().orElseThrow();

        JsonNode legacyCustomer = json(mockMvc.perform(post("/api/v1/customers")
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", "历史A级兼容客户",
                                "phone", "16600009355",
                                "visibility", "PRIVATE",
                                "tagIds", Set.of(tag.get("id").asText())
                        ))))
                .andExpect(status().isCreated()).andReturn());
        UUID legacyCustomerId = UUID.fromString(legacyCustomer.get("id").asText());
        mockMvc.perform(post("/api/v1/customers/{id}/interactions", legacyCustomerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "微信",
                                "summary", "普通时间线不能直接新增 A 类",
                                "tagIds", Set.of(qualityA.get("id").asText())
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STRUCTURED_CLASSIFICATION_REQUIRED"));
        assertThat(jdbc.queryForObject("select count(*) from interactions where customer_id=?",
                Long.class, legacyCustomerId)).isZero();
        jdbc.update("insert into customer_tags (customer_id,tag_id) values (?,?)",
                legacyCustomerId, UUID.fromString(qualityA.get("id").asText()));
        assertThat(jdbc.queryForObject("select count(*) from customer_classifications where customer_id=?",
                Long.class, legacyCustomerId)).isZero();

        mockMvc.perform(post("/api/v1/customers/{id}/interactions", legacyCustomerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "微信",
                                "summary", "历史 A 类客户正常新增沟通",
                                "tagIds", Set.of(qualityA.get("id").asText(), tag.get("id").asText())
                        ))))
                .andExpect(status().isCreated());
        JsonNode legacyAfterInteraction = json(mockMvc.perform(get("/api/v1/customers/{id}", legacyCustomerId)
                        .cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andReturn());
        assertThat(legacyAfterInteraction.get("tags").findValuesAsText("name"))
                .containsExactlyInAnyOrder("A类用户", "全量高意向");

        mockMvc.perform(post("/api/v1/customers/{id}/interactions", legacyCustomerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "电话",
                                "summary", "明确清空普通标签但保留历史等级",
                                "tagIds", Set.of(),
                                "tagUpdateRequested", true
                        ))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/customers/{id}", legacyCustomerId).cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0].name").value("A类用户"));

        mockMvc.perform(post("/api/v1/customers/{id}/interactions", legacyCustomerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "channel", "微信",
                                "summary", "普通时间线不能切换历史等级",
                                "tagIds", Set.of(qualityB.get("id").asText()),
                                "tagUpdateRequested", true
                        ))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STRUCTURED_CLASSIFICATION_REQUIRED"));

        Instant followUp = Instant.now().plusSeconds(7_200);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", "完整生命周期客户");
        body.put("phone", "16600009999");
        body.put("wechatId", "full_flow_wechat");
        body.put("qqId", "99009900");
        body.put("school", school.get("label").asText());
        body.put("major", "计算机科学与技术");
        body.put("grade", "26级");
        body.put("identityType", "在校生");
        body.put("focusDirections", Set.of("升学规划", "新媒体运营"));
        body.put("intentionLevel", "高");
        body.put("urgencyLevel", "高");
        body.put("verificationStatus", "部分待确认");
        body.put("sourceDictionaryId", dictionary.get("id").asText());
        body.put("currentStatus", "跟进中");
        body.put("visibility", "PRIVATE");
        body.put("tagIds", Set.of(tag.get("id").asText()));
        body.put("nextFollowUpAt", followUp.toString());
        Map<String, Object> conflictingQualityBody = new LinkedHashMap<>(body);
        conflictingQualityBody.put("fullName", "质量标签冲突客户");
        conflictingQualityBody.put("phone", "16600009998");
        conflictingQualityBody.put("wechatId", "");
        conflictingQualityBody.put("qqId", "");
        conflictingQualityBody.put("tagIds", Set.of(
                qualityA.get("id").asText(), qualityB.get("id").asText()));
        mockMvc.perform(post("/api/v1/customers").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(conflictingQualityBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOMER_QUALITY_CONFLICT"));
        JsonNode createdCustomer = json(mockMvc.perform(post("/api/v1/customers").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json").content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.school").value("示例大学B"))
                .andExpect(jsonPath("$.focusDirections.length()").value(2))
                .andReturn());
        UUID customerId = UUID.fromString(createdCustomer.get("id").asText());
        JsonNode classificationEvidence = json(mockMvc.perform(post("/api/v1/customers/{id}/interactions", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactedAt", Instant.now().toString(),
                                "channel", "微信",
                                "interactionType", "CUSTOMER_REPLY",
                                "summary", "客户明确回复并表达近期咨询需求"
                        ))))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/customers/{id}/classifications", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "level", "A",
                                "recognitionReason", "客户明确回复并确认近期推进",
                                "potentialDemand", "近期升学规划咨询",
                                "aSignals", List.of("明确近期咨询意愿"),
                                "evidenceInteractionIds", List.of(classificationEvidence.get("id").asText())
                        ))))
                .andExpect(status().isCreated());
        JsonNode customer = json(mockMvc.perform(get("/api/v1/customers/{id}", customerId)
                        .cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tags.length()").value(2)).andReturn());
        assertThat(customer.get("tags").findValuesAsText("category"))
                .containsExactlyInAnyOrder("意向", "客户质量");
        mockMvc.perform(post("/api/v1/customers/{id}/interactions", customerId)
                        .cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactedAt", Instant.now().toString(),
                                "channel", "微信",
                                "summary", "冲突质量标签不应写入",
                                "tagIds", Set.of(qualityA.get("id").asText(), qualityB.get("id").asText())
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CUSTOMER_QUALITY_CONFLICT"));
        mockMvc.perform(get("/api/v1/customers").param("search", "完整生命周期客户")
                        .param("grade", "2026").cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].grade").value("2026"));

        Map<String, Object> optimisticBody = new LinkedHashMap<>();
        optimisticBody.put("fullName", "并发编辑客户");
        optimisticBody.put("phone", "16600007777");
        optimisticBody.put("visibility", "PRIVATE");
        JsonNode optimisticCustomer = json(mockMvc.perform(post("/api/v1/customers")
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(optimisticBody)))
                .andExpect(status().isCreated()).andReturn());
        optimisticBody.put("ownerNotes", "第一位编辑者保存");
        optimisticBody.put("expectedVersion", optimisticCustomer.get("version").asLong());
        mockMvc.perform(patch("/api/v1/customers/{id}", optimisticCustomer.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(optimisticBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerNotes").value("第一位编辑者保存"));
        optimisticBody.put("ownerNotes", "过期页面覆盖");
        mockMvc.perform(patch("/api/v1/customers/{id}", optimisticCustomer.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(optimisticBody)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
        optimisticBody.put("fullName", "完整生命周期客户");
        optimisticBody.put("major", "计算机科学与技术");
        optimisticBody.put("ownerNotes", "同名同专业编辑");
        optimisticBody.put("expectedVersion", optimisticCustomer.get("version").asLong() + 1);
        mockMvc.perform(patch("/api/v1/customers/{id}", optimisticCustomer.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(optimisticBody)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POSSIBLE_DUPLICATE"));
        optimisticBody.put("duplicateOverrideReason", "已核对学号，确认编辑后仍为不同学生");
        mockMvc.perform(patch("/api/v1/customers/{id}", optimisticCustomer.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(optimisticBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("完整生命周期客户"))
                .andExpect(jsonPath("$.major").value("计算机科学与技术"));

        mockMvc.perform(post("/api/v1/customers/duplicate-check").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"fullName\":\"其他名字\",\"phone\":\"166-0000-9999\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocking").value(true));
        mockMvc.perform(post("/api/v1/customers").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"fullName\":\"联系方式重复\",\"phone\":\"16600009999\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXACT_DUPLICATE"));
        mockMvc.perform(post("/api/v1/customers/duplicate-check").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"fullName\":\"完整生命周期客户\",\"phone\":\"16600008888\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocking").value(false))
                .andExpect(jsonPath("$.requiresConfirmation").value(false))
                .andExpect(jsonPath("$.matches.length()").value(0));
        mockMvc.perform(post("/api/v1/customers").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"fullName\":\"完整生命周期客户\",\"phone\":\"16600008888\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/customers/duplicate-check").cookie(memberB1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"fullName\":\"完整生命周期客户\",\"major\":\"计算机科学与技术\",\"phone\":\"16600006666\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocking").value(false))
                .andExpect(jsonPath("$.requiresConfirmation").value(true))
                .andExpect(jsonPath("$.hiddenExactMatches").value(2))
                .andExpect(jsonPath("$.matches.length()").value(0));
        mockMvc.perform(post("/api/v1/customers").cookie(memberB1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"fullName\":\"完整 生命周期客户\",\"major\":\" 计算机科学与技术 \",\"phone\":\"16600006666\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POSSIBLE_DUPLICATE"));
        mockMvc.perform(post("/api/v1/customers").cookie(memberB1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"fullName\":\"完整 生命周期客户\",\"major\":\" 计算机科学与技术 \",\"phone\":\"16600006666\",\"duplicateOverrideReason\":\"已核对学号，确认为不同学生\"}"))
                .andExpect(status().isCreated());

        Instant nextContact = Instant.now().plusSeconds(14_400);
        mockMvc.perform(post("/api/v1/customers/{id}/interactions", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactedAt", Instant.now().toString(), "channel", "微信",
                                "summary", "深度沟通升学方案", "details", "测试事务内数据同步",
                                "nextFollowUpAt", nextContact.toString(), "currentStatus", "高意向",
                                "intentionLevel", "高", "verificationStatus", "已核实",
                                "tagIds", Set.of(tag.get("id").asText())))))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/customers/{id}/interactions", customerId).cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        JsonNode customerReminders = remindersFor(customerId, memberA1.cookie());
        assertThat(customerReminders.size()).isEqualTo(2);
        assertThat(customerReminders.findValuesAsText("status")).containsExactlyInAnyOrder("COMPLETED", "PENDING");

        mockMvc.perform(get("/api/v1/customers/{id}", customerId).cookie(memberA2.cookie()))
                .andExpect(status().isNotFound());
        JsonNode ownerCollaboration = json(mockMvc.perform(
                        get("/api/v1/customers/{id}/collaboration", customerId).cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(true))
                .andExpect(jsonPath("$.canEditProfile").value(true))
                .andExpect(jsonPath("$.canArchive").value(true))
                .andExpect(jsonPath("$.canEditTimeline").value(true))
                .andReturn());
        assertThat(ownerCollaboration.get("candidates").findValuesAsText("displayName"))
                .contains("A组成员2").doesNotContain("A组只读");
        mockMvc.perform(put("/api/v1/customers/{id}/collaborators", customerId)
                        .cookie(memberA2.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "membershipIds", Set.of(memberA2.membershipId())))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/customers/{id}/collaborators", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "membershipIds", Set.of(otherOrganizationMember.membershipId())))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COLLABORATOR_NOT_ELIGIBLE"));
        mockMvc.perform(put("/api/v1/customers/{id}/collaborators", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "membershipIds", Set.of(memberA2.membershipId())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collaborators.length()").value(1))
                .andExpect(jsonPath("$.collaborators[0].displayName").value("A组成员2"));
        mockMvc.perform(get("/api/v1/admin/cross-sales")
                        .param("crossSale", "true")
                        .param("collaboratorMembershipId", memberA2.membershipId().toString())
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.items[0].crossSale").value(true))
                .andExpect(jsonPath("$.items[0].collaboratorCount").value(1))
                .andExpect(jsonPath("$.items[0].collaboratorNames").value("A组成员2"))
                .andExpect(jsonPath("$.items[0].businessCycleId").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/cross-sales").param("crossSale", "true")
                        .cookie(memberA1.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customers/{id}", customerId).cookie(memberA2.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId.toString()));
        mockMvc.perform(get("/api/v1/customers/{id}/collaboration", customerId).cookie(memberA2.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canManage").value(false))
                .andExpect(jsonPath("$.canEditProfile").value(true))
                .andExpect(jsonPath("$.canArchive").value(false))
                .andExpect(jsonPath("$.canEditTimeline").value(true))
                .andExpect(jsonPath("$.candidates.length()").value(0));
        Map<String, Object> collaboratorEditBody = new LinkedHashMap<>(body);
        collaboratorEditBody.put("ownerNotes", "协作者补充客户画像");
        collaboratorEditBody.put("visibility", "ORGANIZATION");
        collaboratorEditBody.put("duplicateOverrideReason", "已核对档案，协作者确认继续更新原客户");
        mockMvc.perform(patch("/api/v1/customers/{id}", customerId)
                        .cookie(memberA2.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(collaboratorEditBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerNotes").value("协作者补充客户画像"))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.ownerMembershipId").value(memberA1.membershipId().toString()));
        mockMvc.perform(post("/api/v1/customers/{id}/archive", customerId)
                        .cookie(memberA2.cookie()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/customers/{id}/interactions", customerId)
                        .cookie(memberA2.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactedAt", Instant.now().toString(), "channel", "QQ",
                                "summary", "协作者共同跟进", "details", "写入同一客户时间线"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByDisplayName").value("A组成员2"));
        mockMvc.perform(get("/api/v1/customers/{id}/interactions", customerId).cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(put("/api/v1/customers/{id}/collaborators", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"membershipIds\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.collaborators.length()").value(0));
        mockMvc.perform(get("/api/v1/customers/{id}", customerId).cookie(memberA2.cookie()))
                .andExpect(status().isNotFound());

        JsonNode manualReminder = json(mockMvc.perform(post("/api/v1/reminders").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of(
                                "customerId", customerId, "remindAt", Instant.now().plusSeconds(20_000).toString(),
                                "title", "手动提醒"))))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(patch("/api/v1/reminders/{id}", manualReminder.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"COMPLETED\",\"title\":\"已完成提醒\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        mockMvc.perform(patch("/api/v1/customers/{id}", customerId).cookie(viewerA.cookie()).with(csrf())
                        .contentType("application/json").content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/customers/{id}/archive", customerId)
                        .cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from reminders where customer_id=? and status='PENDING'",
                Long.class, customerId)).isZero();

        JsonNode archivedDuplicateCandidate = json(mockMvc.perform(post("/api/v1/customers")
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content("""
                                {"fullName":"归档查重隔离客户","major":"归档查重专业",
                                 "phone":"16600005555","visibility":"PRIVATE"}
                                """))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/customers/{id}/archive", archivedDuplicateCandidate.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/customers/duplicate-check").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("""
                                {"fullName":"归档查重隔离客户","major":"归档查重专业",
                                 "phone":"166-0000-5555"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocking").value(false))
                .andExpect(jsonPath("$.requiresConfirmation").value(false))
                .andExpect(jsonPath("$.matches.length()").value(0));
    }

    @Test
    @Order(4)
    void csvXlsxImportExportAndReportsHandleHundredsOfRowsAndMalformedData() throws Exception {
        byte[] csv = MockDataFactory.customerCsv(210, 10, 20_000_000).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "customers-large.csv", "text/csv", csv);
        JsonNode preview = json(mockMvc.perform(multipart("/api/v1/imports/customers/preview")
                        .file(file).cookie(adminCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(220))
                .andExpect(jsonPath("$.readyRows").value(210))
                .andExpect(jsonPath("$.failedRows").value(10))
                .andExpect(jsonPath("$.rows.length()").value(100))
                .andReturn());
        String jobId = preview.get("jobId").asText();
        mockMvc.perform(post("/api/v1/imports/{id}/commit", jobId).cookie(adminCookie).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL_FAILED"))
                .andExpect(jsonPath("$.createdCount").value(210))
                .andExpect(jsonPath("$.failedCount").value(10));
        mockMvc.perform(post("/api/v1/imports/{id}/commit", jobId).cookie(adminCookie).with(csrf()))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/imports/{id}/report", jobId).cookie(adminCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(10));
        mockMvc.perform(get("/api/v1/imports/{id}/report", UUID.randomUUID()).cookie(adminCookie))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMPORT_NOT_FOUND"));

        MockMultipartFile memberFile = new MockMultipartFile("file", "forbidden.csv", "text/csv", csv);
        mockMvc.perform(multipart("/api/v1/imports/customers/preview").file(memberFile)
                        .cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isForbidden());

        MockMultipartFile xlsx = new MockMultipartFile("file", "customers.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsxImportFixture());
        mockMvc.perform(multipart("/api/v1/imports/customers/preview").file(xlsx)
                        .cookie(managerA.cookie()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").value("XLSX"))
                .andExpect(jsonPath("$.readyRows").value(5));

        MvcResult csvExport = mockMvc.perform(get("/api/v1/exports/customers").param("format", "csv")
                        .cookie(adminCookie))
                .andExpect(status().isOk()).andReturn();
        byte[] exportedCsv = csvExport.getResponse().getContentAsByteArray();
        assertThat(exportedCsv).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        assertThat(new String(exportedCsv, StandardCharsets.UTF_8)).contains("规模客户-0001", "批量客户-0001");

        MvcResult xlsxExport = mockMvc.perform(get("/api/v1/exports/customers").param("format", "xlsx")
                        .cookie(managerA.cookie()))
                .andExpect(status().isOk()).andReturn();
        assertThat(xlsxExport.getResponse().getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');

        JsonNode leadership = json(mockMvc.perform(get("/api/v1/leadership-dashboard").param("days", "30")
                        .cookie(managerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastBusinessActivityAt").exists())
                .andExpect(jsonPath("$.headline.totalCustomers").isNumber())
                .andExpect(jsonPath("$.recentCustomerActivities").isArray())
                .andExpect(jsonPath("$.dailyConversions.length()").value(30))
                .andExpect(jsonPath("$.customerQuality.classifiedCustomers").isNumber())
                .andExpect(jsonPath("$.customerQuality.unclassifiedCustomers").isNumber())
                .andExpect(jsonPath("$.customerQuality.levels.length()").value(3))
                .andExpect(jsonPath("$.customerQuality.levels[0].code").value("A"))
                .andExpect(jsonPath("$.taskSummary.assignmentCount").isNumber())
                .andExpect(jsonPath("$.recentTaskPublications").isArray())
                .andExpect(jsonPath("$.rankings").isArray())
                .andExpect(jsonPath("$.rankings[0].qualityACustomers").isNumber())
                .andExpect(jsonPath("$.rankings[0].todayCustomers").isNumber())
                .andExpect(jsonPath("$.sources").isArray())
                .andExpect(jsonPath("$.displayMessages").isArray())
                .andExpect(jsonPath("$.tickerItems.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.featuredGrade").value("2026"))
                .andExpect(jsonPath("$.aClassStale.thresholdHours").value(72))
                .andExpect(jsonPath("$.schoolComparison.featuredGrade").value("2026"))
                .andExpect(jsonPath("$.screenSettings.idleTimeoutMinutes").value(20))
                .andExpect(jsonPath("$.screenSettings.sceneOrder.length()").value(9))
                .andReturn());
        long expectedGrade26RankingCustomers = jdbc.queryForObject("""
                select count(*) from customers customer
                where customer.organization_id=? and customer.archived_at is null
                  and regexp_replace(coalesce(customer.grade, ''), '[^0-9]', '', 'g')
                      in ('26', '2026')
                  and customer.owner_membership_id in (
                    select team_member.membership_id from team_members team_member
                    where team_member.team_id=?
                  )
                """, Long.class, organizationId, teamA);
        long actualGrade26RankingCustomers = 0;
        long actualQualityACustomers = 0;
        long actualTodayCustomers = 0;
        for (JsonNode ranking : leadership.get("rankings")) {
            actualGrade26RankingCustomers += ranking.get("totalCustomers").asLong();
            actualQualityACustomers += ranking.get("qualityACustomers").asLong();
            actualTodayCustomers += ranking.get("todayCustomers").asLong();
        }
        assertThat(actualGrade26RankingCustomers).isEqualTo(expectedGrade26RankingCustomers);
        long expectedQualityACustomers = jdbc.queryForObject("""
                select count(distinct customer.id)
                from customers customer
                join customer_tags customer_tag on customer_tag.customer_id=customer.id
                join tags tag on tag.id=customer_tag.tag_id
                where customer.organization_id=? and customer.archived_at is null
                  and regexp_replace(coalesce(customer.grade, ''), '[^0-9]', '', 'g')
                      in ('26', '2026')
                  and customer.owner_membership_id in (
                    select team_member.membership_id from team_members team_member
                    where team_member.team_id=?
                  )
                  and tag.organization_id=customer.organization_id
                  and tag.active=true and tag.category='客户质量' and tag.name='A类用户'
                """, Long.class, organizationId, teamA);
        long expectedTodayCustomers = jdbc.queryForObject("""
                select count(distinct customer.id)
                from customers customer
                where customer.organization_id=? and customer.archived_at is null
                  and regexp_replace(coalesce(customer.grade, ''), '[^0-9]', '', 'g')
                      in ('26', '2026')
                  and customer.owner_membership_id in (
                    select team_member.membership_id from team_members team_member
                    where team_member.team_id=?
                  )
                  and customer.created_at >=
                      (cast(? as date)::timestamp at time zone 'Asia/Shanghai')
                  and customer.created_at <
                      ((cast(? as date) + 1)::timestamp at time zone 'Asia/Shanghai')
                """, Long.class, organizationId, teamA,
                LocalDate.now(BUSINESS_ZONE), LocalDate.now(BUSINESS_ZONE));
        assertThat(actualQualityACustomers).isEqualTo(expectedQualityACustomers);
        assertThat(actualTodayCustomers).isEqualTo(expectedTodayCustomers);
        MvcResult leadershipExport = mockMvc.perform(
                        get("/api/v1/leadership-dashboard/export.xlsx")
                                .param("days", "30").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn();
        byte[] leadershipWorkbook = leadershipExport.getResponse().getContentAsByteArray();
        assertThat(leadershipWorkbook).startsWith((byte) 'P', (byte) 'K');
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(leadershipWorkbook))) {
            assertThat(workbook.getSheet("领导摘要")).isNotNull();
            assertThat(workbook.getSheet("客户来源分析")).isNotNull();
            assertThat(workbook.getSheet("个人排行榜")).isNotNull();
            assertThat(workbook.getSheet("每日趋势")).isNotNull();
            assertThat(workbook.getSheet("领导摘要").getDrawingPatriarch()).isNotNull();
        }
        mockMvc.perform(get("/api/v1/leadership-dashboard").cookie(memberA1.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/leadership-dashboard/export.xlsx")
                        .cookie(memberA1.cookie()))
                .andExpect(status().isForbidden());

        UUID timelineCustomer = scaleData.firstOwnedBy(memberA1.membershipId()).id();
        MvcResult timeline = mockMvc.perform(get("/api/v1/exports/customers/{id}/timeline", timelineCustomer)
                        .param("format", "csv").cookie(managerA.cookie()))
                .andExpect(status().isOk()).andReturn();
        assertThat(new String(timeline.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("Mock 沟通 0", "Mock 沟通 1");

        mockMvc.perform(get("/api/v1/exports/customers").param("format", "csv").cookie(memberA1.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.traceId").exists());
        UUID hiddenTimeline = scaleData.firstPrivateOwnedBy(managerB.membershipId()).id();
        mockMvc.perform(get("/api/v1/exports/customers/{id}/timeline", hiddenTimeline)
                        .param("format", "csv").cookie(memberA1.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    @Order(5)
    void mandatoryAssignedAndSelfClaimTasksCoverDistributionSubmissionReviewAndCapacity() throws Exception {
        long eligibleBefore = jdbc.queryForObject("""
                select count(distinct m.id) from memberships m
                join membership_roles mr on mr.membership_id=m.id join roles r on r.id=mr.role_id
                where m.organization_id=? and m.status='ACTIVE' and r.code in ('MEMBER','TEAM_MANAGER')
                """, Long.class, organizationId);
        JsonNode mandatory = createTask("MANDATORY", "强制日常任务", null, null);
        assertThat(mandatory.get("assignmentCount").asLong()).isEqualTo(eligibleBefore);

        MemberAccount lateMember = createMember("late-member", "新加入成员", "MEMBER", teamA);
        JsonNode mandatoryAssignments = json(mockMvc.perform(get("/api/v1/tasks/{id}/assignments", mandatory.get("id").asText())
                        .cookie(adminCookie))
                .andExpect(status().isOk()).andReturn());
        assertThat(mandatoryAssignments.size()).isEqualTo((int) eligibleBefore + 1);
        assertThat(findBy(mandatoryAssignments, "membershipId", lateMember.membershipId().toString())
                .get("assignmentSource").asText()).isEqualTo("MANDATORY");

        JsonNode assigned = createTask("ASSIGNED", "定向派发任务", null,
                Set.of(memberA1.membershipId(), memberB1.membershipId()));
        assertThat(assigned.get("assignmentCount").asLong()).isEqualTo(2);
        JsonNode matrix = json(mockMvc.perform(get("/api/v1/tasks/matrix").cookie(adminCookie))
                .andExpect(status().isOk()).andReturn());
        JsonNode assignedMatrixRow = null;
        for (JsonNode row : matrix) {
            if (assigned.get("id").asText().equals(row.get("task").get("id").asText())) {
                assignedMatrixRow = row;
                break;
            }
        }
        assertThat(assignedMatrixRow).isNotNull();
        assertThat(assignedMatrixRow.get("assignments").size()).isEqualTo(2);
        mockMvc.perform(get("/api/v1/tasks/matrix").cookie(memberA1.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/tasks").cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(taskBody("ASSIGNED", "空派发", null, Set.of())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TASK_ASSIGNEE_REQUIRED"));
        mockMvc.perform(post("/api/v1/tasks").cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(taskBody("ASSIGNED", "非法派发", null, Set.of(viewerA.membershipId()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TASK_ASSIGNEE"));

        String assignedId = assigned.get("id").asText();
        mockMvc.perform(patch("/api/v1/tasks/{id}/my-assignment", assignedId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"SUBMITTED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TASK_RESULT_REQUIRED"));
        mockMvc.perform(patch("/api/v1/tasks/{id}/my-assignment", assignedId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk());
        JsonNode submitted = json(mockMvc.perform(patch("/api/v1/tasks/{id}/my-assignment", assignedId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"SUBMITTED\",\"resultSummary\":\"已完成定向任务\",\"evidenceUrl\":\"https://example.com/evidence\"}"))
                .andExpect(status().isOk()).andReturn());
        String assignmentId = submitted.get("id").asText();
        mockMvc.perform(patch("/api/v1/tasks/{taskId}/assignments/{assignmentId}", assignedId, assignmentId)
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"RETURNED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TASK_FEEDBACK_REQUIRED"));
        mockMvc.perform(patch("/api/v1/tasks/{taskId}/assignments/{assignmentId}", assignedId, assignmentId)
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"RETURNED\",\"feedback\":\"需要补充数据\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RETURNED"));
        mockMvc.perform(patch("/api/v1/tasks/{id}/my-assignment", assignedId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"SUBMITTED\",\"resultSummary\":\"已补充完整数据\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/tasks/{taskId}/assignments/{assignmentId}", assignedId, assignmentId)
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        JsonNode selfClaim = createTask("SELF_CLAIM", "单名额领取任务", 1, null);
        mockMvc.perform(post("/api/v1/tasks/{id}/claim", selfClaim.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tasks/{id}/claim", selfClaim.get("id").asText())
                        .cookie(memberB1.cookie()).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TASK_CLAIM_LIMIT_REACHED"));

        JsonNode concurrentTask = createTask("SELF_CLAIM", "并发领取任务", 1, null);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<CompletableFuture<Integer>> attempts = List.of(
                    CompletableFuture.supplyAsync(() -> claimStatus(concurrentTask.get("id").asText(), memberA2.cookie()), executor),
                    CompletableFuture.supplyAsync(() -> claimStatus(concurrentTask.get("id").asText(), memberB2.cookie()), executor)
            );
            List<Integer> statuses = attempts.stream().map(CompletableFuture::join).sorted().toList();
            assertThat(statuses).containsExactly(200, 409);
        }
        assertThat(jdbc.queryForObject("select count(*) from work_task_assignments where task_id=?",
                Long.class, UUID.fromString(concurrentTask.get("id").asText()))).isEqualTo(1);

        mockMvc.perform(post("/api/v1/tasks/{id}/close", concurrentTask.get("id").asText())
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isNoContent());

        JsonNode deletable = createTask("MANDATORY", "尚未执行可删除任务", null, null);
        mockMvc.perform(get("/api/v1/tasks").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(deletable.get("id").asText()));
        mockMvc.perform(delete("/api/v1/tasks/{id}", deletable.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/tasks/{id}", deletable.get("id").asText())
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from work_tasks where id=?::uuid", Long.class,
                deletable.get("id").asText())).isZero();
        mockMvc.perform(delete("/api/v1/tasks/{id}", assignedId).cookie(adminCookie).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_HAS_PROGRESS"));
    }

    @Test
    @Order(6)
    void oneHundredTwentyContentPostsCircleQualityReviewMetricsAndDailyReportsAreCorrect() throws Exception {
        JsonNode direction = createDirection("新生成长与学习规划");
        JsonNode inactiveDirection = createDirection("已停用方向");
        mockMvc.perform(patch("/api/v1/content-directions/{id}", inactiveDirection.get("id").asText())
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "已停用方向", "description", "不再接收内容", "active", false))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));

        List<MemberAccount> contributors = List.of(managerA, memberA1, memberA2, managerB, memberB1, memberB2);
        Map<UUID, Map<String, Integer>> validCounts = new LinkedHashMap<>();
        List<JsonNode> submittedPosts = new ArrayList<>();
        int sequence = 0;
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        for (MemberAccount contributor : contributors) {
            validCounts.put(contributor.membershipId(), new LinkedHashMap<>(Map.of("XIAOHONGSHU", 0, "DOUYIN", 0)));
            for (int day = 0; day < 10; day++) {
                for (String platform : List.of("XIAOHONGSHU", "DOUYIN")) {
                    LocalDate publishDate = today.minusDays(day);
                    JsonNode post = submitContent(contributor.cookie(), direction.get("id").asText(), platform,
                            publishDate, "模拟内容主题-" + sequence, sequence);
                    submittedPosts.add(post);
                    boolean valid = sequence % 5 != 0;
                    String reviewBody = valid
                            ? "{\"status\":\"VALID\"}"
                            : "{\"status\":\"INVALID\",\"reason\":\"质量不符合要求\"}";
                    mockMvc.perform(patch("/api/v1/content-posts/{id}/review", post.get("id").asText())
                                    .cookie(adminCookie).with(csrf()).contentType("application/json").content(reviewBody))
                            .andExpect(status().isOk());
                    if (valid) validCounts.get(contributor.membershipId()).merge(platform, 1, Integer::sum);
                    sequence++;
                }
            }
        }
        assertThat(submittedPosts).hasSize(120);

        JsonNode firstOwnPost = submittedPosts.stream()
                .filter(row -> row.get("membershipId").asText().equals(memberA1.membershipId().toString()))
                .findFirst().orElseThrow();
        mockMvc.perform(patch("/api/v1/content-posts/{id}/metrics", firstOwnPost.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"viewCount\":9999,\"likeCount\":888,\"favoriteCount\":77}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.viewCount").value(9999));
        mockMvc.perform(patch("/api/v1/content-posts/{id}/metrics", firstOwnPost.get("id").asText())
                        .cookie(memberB1.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"viewCount\":1,\"likeCount\":1,\"favoriteCount\":1}"))
                .andExpect(status().isForbidden());

        JsonNode duplicateBase = submittedPosts.stream()
                .filter(row -> row.get("membershipId").asText().equals(memberA1.membershipId().toString()))
                .findFirst().orElseThrow();
        mockMvc.perform(post("/api/v1/content-posts").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json").content(contentBody(
                                direction.get("id").asText(), duplicateBase.get("platform").asText(),
                                LocalDate.parse(duplicateBase.get("publishDate").asText()), "重复内容", 999, true,
                                "3.28 复制打开抖音 https://v.douyin.com/eucZY9gC5Ec/ 07/30 测试分享文案")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DAILY_PLATFORM_LIMIT"));
        mockMvc.perform(post("/api/v1/content-posts").cookie(viewerA.cookie()).with(csrf())
                        .contentType("application/json").content(contentBody(
                                direction.get("id").asText(), "XIAOHONGSHU", today, "只读提交", 1, true,
                                "https://example.com/viewer")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/content-posts").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json").content(contentBody(
                                inactiveDirection.get("id").asText(), "XIAOHONGSHU", today.minusDays(20), "停用方向", 2, true,
                                "https://example.com/inactive")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DIRECTION_INACTIVE"));
        mockMvc.perform(post("/api/v1/content-posts").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json").content(contentBody(
                                direction.get("id").asText(), "XIAOHONGSHU", today.minusDays(21), "未确认原创", 3, false,
                                "https://example.com/not-original")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ORIGINAL_DECLARATION_REQUIRED"));
        mockMvc.perform(post("/api/v1/content-posts").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json").content(contentBody(
                                direction.get("id").asText(), "XIAOHONGSHU", today.plusDays(1), "未来内容", 4, true,
                                "https://example.com/future")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FUTURE_PUBLISH_DATE"));

        JsonNode memberOverview = json(mockMvc.perform(get("/api/v1/content-tasks/overview")
                        .cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andReturn());
        assertThat(memberOverview.get("myPosts").size()).isEqualTo(20);
        assertThat(memberOverview.at("/myProgress/xiaohongshuValid").asInt())
                .isEqualTo(validCounts.get(memberA1.membershipId()).get("XIAOHONGSHU"));
        assertThat(memberOverview.at("/myProgress/douyinValid").asInt())
                .isEqualTo(validCounts.get(memberA1.membershipId()).get("DOUYIN"));

        mockMvc.perform(get("/api/v1/circle/posts").param("size", "100").cookie(viewerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(100))
                .andExpect(jsonPath("$.totalElements").value(120))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/circle/posts").param("page", "1").param("size", "100")
                        .cookie(viewerA.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20));
        mockMvc.perform(get("/api/v1/circle/posts").param("search", "模拟内容主题-42")
                        .cookie(viewerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].weightScore").isNumber());
        mockMvc.perform(get("/api/v1/circle/posts")
                        .param("membershipId", memberA1.membershipId().toString()).param("size", "100")
                        .cookie(viewerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(20))
                .andExpect(jsonPath("$.items[*].membershipId",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(memberA1.membershipId().toString()))));
        mockMvc.perform(get("/api/v1/circle/posts").param("platform", "XIAOHONGSHU").param("size", "100")
                        .cookie(viewerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(60))
                .andExpect(jsonPath("$.items[*].platform",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("XIAOHONGSHU"))));
        mockMvc.perform(get("/api/v1/circle/posts").param("reviewStatus", "VALID").param("size", "100")
                        .cookie(viewerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(96))
                .andExpect(jsonPath("$.items[*].reviewStatus",
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("VALID"))));
        mockMvc.perform(get("/api/v1/circle/posts").param("sort", "recommended").param("size", "2")
                        .cookie(viewerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].weightScore").isNumber());
        mockMvc.perform(get("/api/v1/circle/senders").cookie(viewerA.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
        mockMvc.perform(get("/api/v1/content-tasks/overview").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewQueue.length()").value(0))
                .andExpect(jsonPath("$.memberProgress.length()").value(7));

        jdbc.update("""
                update customers set lead_review_status='VALID', lead_reviewed_at=now()
                where organization_id=? and grade in ('26级','2026级') and archived_at is null
                  and current_status not in ('无效','归档')
                """, organizationId);
        String reportMonth = today.toString().substring(0, 7);
        int expectedReportMembers = jdbc.queryForObject("""
                select count(distinct m.id)
                from memberships m
                join user_accounts u on u.id=m.user_id and u.enabled=true
                where m.organization_id=? and m.status='ACTIVE'
                  and exists (
                    select 1 from membership_roles mr join roles r on r.id=mr.role_id
                    where mr.membership_id=m.id and r.code in ('MEMBER','TEAM_MANAGER')
                  )
                """, Integer.class, organizationId);
        int expectedManagerMembers = jdbc.queryForObject("""
                select count(distinct m.id)
                from memberships m
                join user_accounts u on u.id=m.user_id and u.enabled=true
                where m.organization_id=? and m.status='ACTIVE'
                  and exists (
                    select 1 from membership_roles mr join roles r on r.id=mr.role_id
                    where mr.membership_id=m.id and r.code in ('MEMBER','TEAM_MANAGER')
                  )
                  and (m.id=? or exists (
                    select 1 from team_members tm where tm.membership_id=m.id and tm.team_id=?
                  ))
                """, Integer.class, organizationId, managerA.membershipId(), teamA);
        mockMvc.perform(get("/api/v1/daily-reports/calendar").param("month", reportMonth).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(reportMonth))
                .andExpect(jsonPath("$.expectedMemberCount").value(expectedReportMembers))
                .andExpect(jsonPath("$.days[?(@.date == '" + today + "')].submittedCount")
                        .value(org.hamcrest.Matchers.contains(0)))
                .andExpect(jsonPath("$.days[?(@.date == '" + today + "')].missingMembers.length()")
                        .value(org.hamcrest.Matchers.contains(expectedReportMembers)));
        mockMvc.perform(get("/api/v1/daily-reports/calendar").param("month", reportMonth).cookie(managerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expectedMemberCount").value(expectedManagerMembers));
        mockMvc.perform(get("/api/v1/daily-reports/calendar").param("month", reportMonth).cookie(viewerA.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/daily-reports/calendar").param("month", "2026-99").cookie(adminCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPORT_MONTH"));
        for (MemberAccount contributor : contributors) {
            long expectedLeads = jdbc.queryForObject("""
                    select count(*) from customers where organization_id=? and created_by_membership_id=?
                    and lead_review_status='VALID' and archived_at is null
                    and created_at >= (?::date::timestamp at time zone 'Asia/Shanghai')
                    and created_at < ((?::date + 1)::timestamp at time zone 'Asia/Shanghai')
                    """, Long.class, organizationId, contributor.membershipId(), today, today);
            mockMvc.perform(put("/api/v1/daily-reports/{date}", today)
                            .cookie(contributor.cookie()).with(csrf()).contentType("application/json")
                            .content("{\"deepCommunicationCount\":8,\"todayIssues\":\"需要补充素材\",\"tomorrowPlan\":\"回访并发布内容\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.grade26NewValidLeads").value(expectedLeads));
        }
        mockMvc.perform(get("/api/v1/daily-reports/calendar").param("month", reportMonth).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days[?(@.date == '" + today + "')].submittedCount")
                        .value(org.hamcrest.Matchers.contains(6)))
                .andExpect(jsonPath("$.days[?(@.date == '" + today + "')].missingMembers.length()")
                        .value(org.hamcrest.Matchers.contains(expectedReportMembers - 6)));
        mockMvc.perform(put("/api/v1/daily-reports/{date}", today).cookie(viewerA.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"deepCommunicationCount\":0,\"todayIssues\":\"无\",\"tomorrowPlan\":\"无\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/daily-reports/{date}", today.plusDays(1)).cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"deepCommunicationCount\":0,\"todayIssues\":\"无\",\"tomorrowPlan\":\"无\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FUTURE_REPORT_DATE"));
        mockMvc.perform(get("/api/v1/daily-reports").cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/api/v1/daily-reports").cookie(managerA.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mockMvc.perform(get("/api/v1/daily-reports").cookie(adminCookie))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));

        mockMvc.perform(get("/api/v1/team-data/overview").param("teamId", teamA.toString())
                        .cookie(managerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(4))
                .andExpect(jsonPath("$.stats.memberCount").value(4))
                .andExpect(jsonPath("$.dailyReports.length()").value(3))
                .andExpect(jsonPath("$.contentProgress.length()").value(4))
                .andExpect(jsonPath("$.administrator").value(false));
        mockMvc.perform(get("/api/v1/team-data/overview").param("teamId", teamB.toString())
                        .cookie(managerA.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/team-data/overview").cookie(viewerA.cookie()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/team-data/overview").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(7))
                .andExpect(jsonPath("$.administrator").value(true));

        JsonNode filteredCustomerPage = json(mockMvc.perform(get("/api/v1/customers")
                        .param("teamId", teamA.toString())
                        .param("search", "规模客户")
                        .param("grade", "2026")
                        .param("page", "0")
                        .param("size", "50")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andReturn());
        long filteredCustomerTotal = filteredCustomerPage.get("totalElements").asLong();
        assertThat(filteredCustomerTotal).isGreaterThan(100);
        JsonNode filteredTeamOverview = json(mockMvc.perform(get("/api/v1/team-data/overview")
                        .param("teamId", teamA.toString())
                        .param("search", "规模客户")
                        .param("grade", "2026")
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(filteredTeamOverview.at("/stats/activeCustomerCount").asLong())
                .isEqualTo(filteredCustomerTotal);
        assertThat(filteredTeamOverview.at("/stats/memberCount").asLong()).isEqualTo(4);
    }

    @Test
    @Order(7)
    void disabledAiAuditOpenApiAndErrorEnvelopeRemainAvailableAfterFullLoad() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/availability").cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.provider").value("disabled"));
        mockMvc.perform(post("/api/v1/assistant/customer-draft").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json")
                        .content("{\"text\":\"张三，手机13800138000\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        mockMvc.perform(post("/api/v1/assistant/customer-draft").cookie(viewerA.cookie()).with(csrf())
                        .contentType("application/json").content("{\"text\":\"测试\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from assistant_runs where status='FAILED'", Long.class)).isPositive();

        JsonNode audit = json(mockMvc.perform(get("/api/v1/admin/audit").param("size", "100")
                        .cookie(adminCookie))
                .andExpect(status().isOk()).andReturn());
        assertThat(audit.get("totalElements").asLong()).isGreaterThan(250);
        mockMvc.perform(get("/api/v1/admin/audit").cookie(memberA1.cookie()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/tasks']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/content-tasks/overview']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/daily-reports/{date}']").exists());
    }

    @Test
    @Order(8)
    void enterpriseForumSupportsPublicDetailsMemberCollaborationAndModeration() throws Exception {
        JsonNode boards = json(mockMvc.perform(get("/api/v1/forum/public/boards"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn());
        String boardId = boards.get(0).get("id").asText();

        mockMvc.perform(post("/api/v1/forum/posts").cookie(viewerA.cookie()).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "boardId", boardId, "title", "只读账号不能发帖", "content", "不应创建"))))
                .andExpect(status().isForbidden());

        JsonNode created = json(mockMvc.perform(post("/api/v1/forum/posts")
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "boardId", boardId,
                                "title", "客户沟通经验复盘",
                                "content", "# 沟通复盘\n先确认需求，再给出下一步建议。",
                                "tags", List.of("沟通", "复盘")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("客户沟通经验复盘"))
                .andReturn());
        String postId = created.get("id").asText();

        byte[] pngContent = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47};
        MockMultipartFile image = new MockMultipartFile(
                "file", "preview.png", "image/png", pngContent);
        JsonNode uploadedImage = json(mockMvc.perform(multipart("/api/v1/forum/posts/{id}/attachments", postId)
                        .file(image).cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andReturn());
        mockMvc.perform(get(uploadedImage.get("url").asText()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("inline;")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        MockMultipartFile document = new MockMultipartFile(
                "file", "preview.pdf", "application/pdf", "%PDF-1.7 test".getBytes(StandardCharsets.UTF_8));
        JsonNode uploadedPdf = json(mockMvc.perform(multipart("/api/v1/forum/posts/{id}/attachments", postId)
                        .file(document).cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("application/pdf"))
                .andReturn());
        mockMvc.perform(get(uploadedPdf.get("url").asText()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("inline;")));

        mockMvc.perform(get("/api/v1/forum/public/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(postId))
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.attachments.length()").value(2));
        mockMvc.perform(get("/api/v1/forum/public/posts").param("query", "沟通复盘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value(postId));

        mockMvc.perform(post("/api/v1/forum/posts/{id}/comments", postId)
                        .cookie(memberA2.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"content\":\"这个方法很实用\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorDisplayName").value("A组成员2"));
        mockMvc.perform(post("/api/v1/forum/posts/{id}/like", postId)
                        .cookie(memberA2.cookie()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liked").value(true))
                .andExpect(jsonPath("$.likeCount").value(1));
        mockMvc.perform(post("/api/v1/forum/posts/{id}/report", postId)
                        .cookie(memberA2.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"reason\":\"测试举报流程\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/forum/manage/reports").cookie(managerA.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].postId").value(postId));
        mockMvc.perform(post("/api/v1/forum/manage/posts/{id}/actions", postId)
                        .cookie(managerA.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"action\":\"LOCK\",\"reason\":\"讨论暂时结束\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true));
        mockMvc.perform(post("/api/v1/forum/posts/{id}/comments", postId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"content\":\"锁定后不能评论\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FORUM_POST_LOCKED"));

        mockMvc.perform(get("/api/v1/forum/manage/overview").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishedPosts").value(1))
                .andExpect(jsonPath("$.openReports").value(1));
    }

    @Test
    @Order(9)
    void configurablePerformanceCenterAuditsEvidenceActivitiesLedgerAndSettlement() throws Exception {
        LocalDate today = LocalDate.now(BUSINESS_ZONE);
        // Keep the end-to-end flow independent from the weekday on which CI runs.
        jdbc.update("update performance_rule_versions set calendar_mode='ALL_DAYS', weekdays='[1,2,3,4,5,6,7]'::jsonb, "
                        + "submission_opens_at='00:00:00', submission_closes_at='23:59:59' "
                        + "where organization_id=? and effective_from<=?",
                organizationId, today);
        mockMvc.perform(post("/api/v1/performance/admin/participants").cookie(viewerA.cookie()).with(csrf())
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of(
                                "membershipId", memberA1.membershipId(), "startsOn", today))))
                .andExpect(status().isForbidden());

        JsonNode participant = json(mockMvc.perform(post("/api/v1/performance/admin/participants")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "membershipId", memberA1.membershipId(), "startsOn", today))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.activeToday").value(true)).andReturn());
        mockMvc.perform(post("/api/v1/performance/admin/participants").cookie(adminCookie).with(csrf())
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of(
                                "membershipId", memberA1.membershipId(), "startsOn", today))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERFORMANCE_PARTICIPANT_OVERLAP"));

        mockMvc.perform(get("/api/v1/performance/today").cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participant").value(true))
                .andExpect(jsonPath("$.rule.dailyBaseline").value(14.0))
                .andExpect(jsonPath("$.rule.eventTiers[0].label").value("Pro"));

        JsonNode customerPage = json(mockMvc.perform(get("/api/v1/customers")
                        .param("ownerMembershipId", memberA1.membershipId().toString())
                        .param("size", "1").cookie(adminCookie))
                .andExpect(status().isOk()).andReturn());
        String customerId = customerPage.at("/items/0/id").asText();
        JsonNode interaction = json(mockMvc.perform(post("/api/v1/customers/{id}/interactions", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactedAt", Instant.now().toString(), "channel", "微信",
                                "summary", "绩效测试深度交流", "details", "客户明确回复并确认下一步安排"))))
                .andExpect(status().isCreated()).andReturn());

        JsonNode claim = json(mockMvc.perform(post("/api/v1/performance/claims")
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "interactionId", interaction.get("id").asText(), "requestedType", "DEEP"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn());
        mockMvc.perform(post("/api/v1/performance/claims").cookie(memberA1.cookie()).with(csrf())
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of(
                                "interactionId", interaction.get("id").asText(), "requestedType", "DEEP"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PERFORMANCE_INTERACTION_ALREADY_CLAIMED"));

        MockMultipartFile invalidEvidence = new MockMultipartFile(
                "file", "fake.png", "image/png", "not-an-image".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/performance/claims/{id}/evidence", claim.get("id").asText())
                        .file(invalidEvidence).cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVIDENCE_TYPE"));
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        JsonNode evidence = json(mockMvc.perform(multipart("/api/v1/performance/claims/{id}/evidence", claim.get("id").asText())
                        .file(new MockMultipartFile("file", "claim.png", "image/png", png))
                        .cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/performance/claims/{id}/submit", claim.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(post("/api/v1/performance/claims/{id}/review", claim.get("id").asText())
                        .cookie(managerA.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "APPROVE", "approvedType", "DEEP", "expectedVersion", 1))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/performance/claims/{id}/review", claim.get("id").asText())
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "APPROVE", "approvedType", "DEEP", "expectedVersion", 1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(get("/api/v1/performance/evidence/{id}", evidence.get("id").asText())
                        .cookie(otherOrganizationMember.cookie()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/performance/claims")
                        .param("membershipId", memberA2.membershipId().toString()).cookie(memberA1.cookie()))
                .andExpect(status().isForbidden());

        JsonNode activity = json(mockMvc.perform(post("/api/v1/performance/activities")
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "eventDate", today, "title", "绩效测试迎新活动", "goal", "验证活动闭环",
                                "expectedParticipants", 60, "description", "组织现场咨询与答疑"))))
                .andExpect(status().isCreated()).andReturn());
        JsonNode approvedActivity = json(mockMvc.perform(post("/api/v1/performance/activities/{id}/review", activity.get("id").asText())
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "APPROVE", "expectedVersion", 0))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED")).andReturn());
        mockMvc.perform(multipart("/api/v1/performance/activities/{id}/evidence", activity.get("id").asText())
                        .file(new MockMultipartFile("file", "activity.png", "image/png", png))
                        .cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isCreated());
        JsonNode completedActivity = json(mockMvc.perform(post("/api/v1/performance/activities/{id}/complete", activity.get("id").asText())
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "actualParticipants", 55, "expectedVersion", approvedActivity.get("version").asLong()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETION_SUBMITTED")).andReturn());
        mockMvc.perform(post("/api/v1/performance/activities/{id}/allocate", activity.get("id").asText())
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "verifiedParticipants", 55,
                                "allocations", List.of(Map.of("membershipId", memberA1.membershipId(), "points", 8.0)),
                                "expectedVersion", completedActivity.get("version").asLong()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.totalPoints").value(8.0));

        JsonNode logistics = json(mockMvc.perform(post("/api/v1/performance/admin/manual-entries")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "membershipId", memberA1.membershipId(), "performanceDate", today,
                                "entryType", "LOGISTICS", "title", "值班后勤", "reason", "完成现场物料整理",
                                "points", 4.0))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.points").value(4.0)).andReturn());
        mockMvc.perform(get("/api/v1/performance/today").cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score.businessPoints").value(10.0))
                .andExpect(jsonPath("$.score.logisticsPoints").value(4.0))
                .andExpect(jsonPath("$.score.shortfall").value(0.0))
                .andExpect(jsonPath("$.score.finalPoints").value(14.0));
        mockMvc.perform(get("/api/v1/daily-reports/today").cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.performanceManaged").value(true))
                .andExpect(jsonPath("$.deepCommunicationCount").value(1));

        Map<String, Object> futureRuleBody = new LinkedHashMap<>();
        futureRuleBody.put("effectiveFrom", today.plusDays(10));
        futureRuleBody.put("dailyBaseline", 20.0);
        futureRuleBody.put("calendarMode", "ALL_DAYS");
        futureRuleBody.put("weekdays", List.of(1, 2, 3, 4, 5, 6, 7));
        futureRuleBody.put("includedDates", List.of());
        futureRuleBody.put("excludedDates", List.of());
        futureRuleBody.put("submissionOpensAt", "08:30");
        futureRuleBody.put("submissionClosesAt", "21:30");
        futureRuleBody.put("shortfallMode", "DEDUCT_GAP");
        futureRuleBody.put("ordinaryPoints", 1.0);
        futureRuleBody.put("deepPoints", 2.0);
        futureRuleBody.put("eventTiers", List.of(Map.of(
                "label", "Pro", "minParticipants", 50, "points", 8.0)));
        JsonNode futureRule = json(mockMvc.perform(post("/api/v1/performance/admin/rules")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(futureRuleBody)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.versionNo").value(2)).andReturn());
        assertThat(futureRule.get("dailyBaseline").decimalValue()).isEqualByComparingTo("20.0");
        mockMvc.perform(get("/api/v1/performance/today").cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rule.dailyBaseline").value(14.0));

        jdbc.update("update performance_days set status='REVIEWING', version=version+1 where organization_id=? and performance_date=?",
                organizationId, today);
        JsonNode reviewDay = json(mockMvc.perform(get("/api/v1/performance/admin/days/{date}", today)
                        .cookie(adminCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingClaims").value(0)).andExpect(jsonPath("$.pendingActivities").value(0))
                .andReturn());
        JsonNode settled = json(mockMvc.perform(post("/api/v1/performance/admin/days/{date}/settle", today)
                        .param("expectedVersion", reviewDay.get("version").asText())
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.members[0].settled").value(true)).andReturn());
        mockMvc.perform(post("/api/v1/performance/admin/manual-entries").cookie(adminCookie).with(csrf())
                        .contentType("application/json").content(objectMapper.writeValueAsString(Map.of(
                                "membershipId", memberA1.membershipId(), "performanceDate", today,
                                "entryType", "LOGISTICS", "title", "锁定后写入", "reason", "不允许",
                                "points", 1.0))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PERFORMANCE_DAY_SETTLED"));
        JsonNode reopened = json(mockMvc.perform(post("/api/v1/performance/admin/days/{date}/reopen", today)
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", "补录纠错回归测试", "expectedVersion", settled.get("version").asLong()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OPEN")).andReturn());
        assertThat(reopened.get("version").asLong()).isGreaterThan(settled.get("version").asLong());
        mockMvc.perform(post("/api/v1/performance/admin/manual-entries")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "membershipId", memberA1.membershipId(), "performanceDate", today,
                                "entryType", "CORRECTION", "title", "后勤纠错", "reason", "冲正误计一分",
                                "points", -1.0, "relatedLedgerId", logistics.get("id").asText()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.points").value(-1.0));
        assertThat(jdbc.queryForObject("select count(*) from performance_score_ledger where organization_id=? and membership_id=?",
                Long.class, organizationId, memberA1.membershipId())).isEqualTo(4L);
        jdbc.update("update performance_days set status='REVIEWING', version=version+1 where organization_id=? and performance_date=?",
                organizationId, today);
        JsonNode secondReview = json(mockMvc.perform(get("/api/v1/performance/admin/days/{date}", today)
                        .cookie(adminCookie)).andExpect(status().isOk()).andReturn());
        mockMvc.perform(post("/api/v1/performance/admin/days/{date}/settle", today)
                        .param("expectedVersion", secondReview.get("version").asText())
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.members[0].score.finalPoints").value(13.0));
        assertThat(jdbc.queryForObject("""
                select count(*) from performance_daily_settlements
                where organization_id=? and performance_date=? and membership_id=?
                """, Long.class, organizationId, today, memberA1.membershipId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                select count(*) from performance_daily_settlements
                where organization_id=? and performance_date=? and membership_id=? and active=true
                """, Long.class, organizationId, today, memberA1.membershipId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from performance_daily_settlements
                where organization_id=? and performance_date=? and membership_id=?
                  and active=false and reopened_reason='补录纠错回归测试'
                """, Long.class, organizationId, today, memberA1.membershipId())).isEqualTo(1L);
        mockMvc.perform(get("/api/v1/performance/history")
                        .param("from", today.withDayOfMonth(1).toString()).param("to", today.toString())
                        .cookie(memberA1.cookie()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].finalPoints").value(13.0));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/performance/today']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/performance/admin/days/{date}/settle']").exists());
        assertThat(participant.get("membershipId").asText()).isEqualTo(memberA1.membershipId().toString());
    }

    @Test
    @Order(10)
    void aClassRecruitmentStaleReminderConsultationAndEnrichmentFormOneClosedLoop() throws Exception {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String customerName = "协作闭环客户-" + unique;
        String phone = "137" + String.format("%08d", Math.floorMod(unique.hashCode(), 100_000_000));
        JsonNode customer = json(mockMvc.perform(post("/api/v1/customers")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", customerName, "phone", phone,
                                "major", "计算机科学与技术-" + unique, "grade", "2026",
                                "ownerMembershipId", memberA1.membershipId(), "teamId", teamA,
                                "visibility", "PRIVATE"))))
                .andExpect(status().isCreated()).andReturn());
        UUID customerId = UUID.fromString(customer.get("id").asText());

        JsonNode evidence = json(mockMvc.perform(post("/api/v1/customers/{id}/interactions", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactedAt", Instant.now(), "channel", "QQ",
                                "interactionType", "CUSTOMER_REPLY",
                                "summary", "客户明确回复并确认近期咨询目标"
                        ))))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/customers/{id}/classifications", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "level", "A",
                                "recognitionReason", "客户明确回复并进入近期咨询推进",
                                "potentialDemand", "计算机方向咨询",
                                "aSignals", List.of("已确认咨询目标"),
                                "evidenceInteractionIds", List.of(evidence.get("id").asText())
                        ))))
                .andExpect(status().isCreated());

        JsonNode recruitments = json(mockMvc.perform(get("/api/v1/second-line-recruitments")
                        .cookie(memberA2.cookie()))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode recruitment = java.util.stream.StreamSupport.stream(recruitments.spliterator(), false)
                .filter(item -> customerId.toString().equals(item.path("customerId").asText()))
                .findFirst().orElseThrow();
        String recruitmentId = recruitment.get("id").asText();
        mockMvc.perform(post("/api/v1/second-line-recruitments/{id}/claim", recruitmentId)
                        .cookie(memberA2.cookie()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeSecondLineCount").value(1))
                .andExpect(jsonPath("$.recruitmentClosed").value(true));
        mockMvc.perform(post("/api/v1/second-line-recruitments/{id}/claim", recruitmentId)
                        .cookie(memberB1.cookie()).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SECOND_LINE_RECRUITMENT_CLOSED"));
        assertThat(jdbc.queryForObject("""
                select count(*) from customer_access_grants where customer_id=? and grantee_membership_id=? and can_edit=true
                """, Long.class, customerId, memberA2.membershipId())).isEqualTo(1);

        String webhookName = "A类超时提醒-" + unique;
        mockMvc.perform(post("/api/v1/admin/wecom-webhooks")
                        .cookie(adminCookie).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", webhookName,
                                "webhookUrl", "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abcdefghijklmnop1234",
                                "eventTypes", List.of("A_CLASS_STALE"),
                                "active", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secretConfigured").value(true));
        jdbc.update("update organization_follow_up_policies set notify_at='00:00', stale_after_hours=72 where organization_id=?",
                organizationId);
        jdbc.update("update customers set created_at=now()-interval '4 days', last_contact_at=null where id=?", customerId);
        followUps.createStaleReminders();
        followUps.createStaleReminders();
        assertThat(jdbc.queryForObject("""
                select count(*) from reminders where customer_id=? and reminder_type='A_CLASS_STALE' and status='PENDING'
                """, Long.class, customerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from system_notifications
                where organization_id=? and event_type='A_CLASS_STALE' and target_path=?
                """, Long.class, organizationId, "/customers/" + customerId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from wecom_webhook_deliveries delivery
                join wecom_webhook_channels channel on channel.id=delivery.channel_id
                where delivery.organization_id=? and delivery.event_type='A_CLASS_STALE'
                  and channel.name=?
                """, Long.class, organizationId, webhookName)).isEqualTo(1);
        mockMvc.perform(get("/api/v1/reminders/urgent-a-class").cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].customerId").value(customerId.toString()))
                .andExpect(jsonPath("$[0].reminderType").value("A_CLASS_STALE"));

        JsonNode interaction = json(mockMvc.perform(post("/api/v1/customers/{id}/interactions", customerId)
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "contactedAt", Instant.now(), "channel", "微信", "summary", "确认咨询目标",
                                "interactionType", "CUSTOMER_REPLY",
                                "details", "客户明确希望了解学习规划并同意安排咨询"))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(jdbc.queryForObject("""
                select count(*) from reminders where customer_id=? and reminder_type='A_CLASS_STALE' and status='PENDING'
                """, Long.class, customerId)).isZero();

        Instant scheduledStart = LocalDate.now(BUSINESS_ZONE).plusDays(1).atTime(10, 0)
                .atZone(BUSINESS_ZONE).toInstant();
        Instant scheduledEnd = scheduledStart.plusSeconds(60 * 60);
        Map<String, Object> handoffBody = new LinkedHashMap<>();
        handoffBody.put("customerId", customerId);
        handoffBody.put("consultantMembershipId", memberA2.membershipId());
        handoffBody.put("sourceInteractionIds", List.of(interaction.get("id").asText()));
        handoffBody.put("consultationReason", "客户希望获得进一步咨询");
        handoffBody.put("customerQuotes", "我希望了解下一步学习规划");
        handoffBody.put("goalsMotivation", "明确学习方向");
        handoffBody.put("actionsTaken", "已完成基础沟通");
        handoffBody.put("mainConcerns", "时间安排");
        handoffBody.put("decisionParticipantConsent", false);
        handoffBody.put("productFit", "适合规划咨询");
        handoffBody.put("suggestedFocus", "课程与时间线");
        handoffBody.put("explicitTaboos", "不得承诺无法核实的结果");
        handoffBody.put("expectedResult", "形成下一步方案");
        handoffBody.put("scheduledStart", scheduledStart);
        handoffBody.put("scheduledEnd", scheduledEnd);
        JsonNode handoff = json(mockMvc.perform(post("/api/v1/consultations")
                        .cookie(memberA1.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(handoffBody)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_ACCEPTANCE"))
                .andExpect(jsonPath("$.customerSnapshot.fullName").value(customerName)).andReturn());
        String consultationId = handoff.get("id").asText();
        JsonNode accepted = json(mockMvc.perform(post("/api/v1/consultations/{id}/accept", consultationId)
                        .cookie(memberA2.cookie()).with(csrf()).contentType("application/json")
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SCHEDULED")).andReturn());
        JsonNode completed = json(mockMvc.perform(post("/api/v1/consultations/{id}/complete", consultationId)
                        .cookie(memberA2.cookie()).with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedVersion", accepted.get("version").asLong(),
                                "resultSummary", "已完成规划咨询并确认推进", "continueFollowUp", true,
                                "nextStep", "三天后确认执行进度", "nextOwnerMembershipId", memberA2.membershipId(),
                                "nextFollowUpAt", Instant.now().plusSeconds(3 * 86400)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.resultInteractionId").isString()).andReturn());
        assertThat(jdbc.queryForObject("""
                select assigned_membership_id from reminders where interaction_id=? and status='PENDING'
                """, UUID.class, UUID.fromString(completed.get("resultInteractionId").asText())))
                .isEqualTo(memberA2.membershipId());
        mockMvc.perform(get("/api/v1/consultations/{id}", consultationId)
                        .cookie(otherOrganizationMember.cookie()))
                .andExpect(status().isNotFound());

        byte[] workbook = enrichmentFixture(customerName, "计算机科学与技术-" + unique, "2026",
                "测试高级中学-" + unique, "示例省示例市");
        JsonNode preview = json(mockMvc.perform(multipart("/api/v1/admin/customer-enrichment-jobs/preview")
                .file(new MockMultipartFile("file", "学生所在地补全.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbook))
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.job.readyRows").value(1)).andReturn());
        String jobId = preview.at("/job/id").asText();
        mockMvc.perform(post("/api/v1/admin/customer-enrichment-jobs/{id}/commit", jobId)
                        .cookie(adminCookie).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.job.updatedRows").value(1));
        mockMvc.perform(get("/api/v1/customers/{id}", customerId).cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.highSchool").value("测试高级中学-" + unique))
                .andExpect(jsonPath("$.sourceLocation").value("示例省示例市"));
        mockMvc.perform(get("/api/v1/admin/customer-enrichment-jobs").cookie(memberA1.cookie()))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(11)
    void productReleaseHistoryIsGlobalAndAcknowledgementIsPerAccountAndIdempotent() throws Exception {
        mockMvc.perform(get("/api/v1/releases"))
                .andExpect(status().isUnauthorized());

        Long publishedReleaseCount = jdbc.queryForObject(
                "select count(*) from product_releases where published", Long.class);
        JsonNode history = json(mockMvc.perform(get("/api/v1/releases")
                        .param("page", "0").param("size", "20").cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(publishedReleaseCount))
                .andExpect(jsonPath("$.items[0].version").value("2026.09.07.2"))
                .andExpect(jsonPath("$.items[0].sections.新增").isArray())
                .andExpect(jsonPath("$.items[0].sections.安全").isArray())
                .andReturn());
        String releaseId = history.get("items").get(0).get("id").asText();

        mockMvc.perform(get("/api/v1/releases/current").cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.acknowledged").value(false));
        mockMvc.perform(get("/api/v1/releases/current/unacknowledged").cookie(memberA1.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(releaseId));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/releases/{releaseId}/acknowledge", releaseId)
                            .cookie(memberA1.cookie()).with(csrf()))
                    .andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from product_release_acknowledgements
                where release_id=? and user_id=(select user_id from memberships where id=?)
                """, Long.class, UUID.fromString(releaseId), memberA1.membershipId())).isEqualTo(1);
        mockMvc.perform(get("/api/v1/releases/current/unacknowledged").cookie(memberA1.cookie()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/releases/current/unacknowledged")
                        .cookie(otherOrganizationMember.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("2026.09.07.2"));
        mockMvc.perform(post("/api/v1/releases/{releaseId}/acknowledge", UUID.randomUUID())
                        .cookie(memberA1.cookie()).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RELEASE_NOT_FOUND"));
    }

    private JsonNode createTeam(String name) throws Exception {
        return json(mockMvc.perform(post("/api/v1/admin/teams").cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("name", name))))
                .andExpect(status().isCreated()).andReturn());
    }

    private MemberAccount createMember(String username, String displayName, String role, UUID teamId) throws Exception {
        JsonNode member = json(mockMvc.perform(post("/api/v1/admin/members").cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "displayName", displayName, "initialPassword", MEMBER_PASSWORD,
                                "role", role, "teamId", teamId))))
                .andExpect(status().isCreated()).andReturn());
        return new MemberAccount(username, UUID.fromString(member.get("membershipId").asText()),
                teamId, role, login(username, MEMBER_PASSWORD));
    }

    private MemberAccount createOtherOrganizationFixture() throws Exception {
        UUID otherOrg = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID otherMembership = UUID.randomUUID();
        UUID otherTeam = UUID.randomUUID();
        String username = "other-org-member";
        jdbc.update("insert into organizations (id, name, code) values (?, ?, ?)", otherOrg, "隔离测试组织", "isolated-test");
        jdbc.update("insert into organization_settings (organization_id) values (?)", otherOrg);
        jdbc.update("insert into user_accounts (id, username, password_hash, display_name) values (?, ?, ?, ?)",
                otherUser, username, passwordEncoder.encode(MEMBER_PASSWORD), "隔离组织成员");
        jdbc.update("insert into memberships (id, organization_id, user_id) values (?, ?, ?)",
                otherMembership, otherOrg, otherUser);
        jdbc.update("insert into membership_roles (membership_id, role_id) values (?, '00000000-0000-0000-0000-000000000003')",
                otherMembership);
        jdbc.update("insert into teams (id, organization_id, name) values (?, ?, ?)", otherTeam, otherOrg, "隔离组");
        jdbc.update("insert into team_members (team_id, membership_id) values (?, ?)", otherTeam, otherMembership);
        MockDataFactory.seedCustomerScale(jdbc, otherOrg,
                List.of(new MockDataFactory.MemberSeed(otherMembership, otherTeam)), 40, "隔离组织客户");
        return new MemberAccount(username, otherMembership, otherTeam, "MEMBER", login(username, MEMBER_PASSWORD));
    }

    private Cookie login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getCookie("YINGGE_SESSION");
    }

    private JsonNode remindersFor(UUID customerId, Cookie cookie) throws Exception {
        JsonNode all = json(mockMvc.perform(get("/api/v1/reminders").cookie(cookie))
                .andExpect(status().isOk()).andReturn());
        var selected = objectMapper.createArrayNode();
        all.forEach(item -> {
            if (item.get("customerId").asText().equals(customerId.toString())) selected.add(item);
        });
        return selected;
    }

    private JsonNode createTask(String type, String title, Integer maxClaimants, Set<UUID> assignees) throws Exception {
        return json(mockMvc.perform(post("/api/v1/tasks").cookie(adminCookie).with(csrf())
                        .contentType("application/json").content(taskBody(type, title, maxClaimants, assignees)))
                .andExpect(status().isCreated()).andReturn());
    }

    private String taskBody(String type, String title, Integer maxClaimants, Set<UUID> assignees) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", title);
        body.put("description", title + " - 全量测试说明");
        body.put("distributionType", type);
        body.put("startAt", Instant.now().minusSeconds(60).toString());
        body.put("dueAt", Instant.now().plusSeconds(86_400).toString());
        if (maxClaimants != null) body.put("maxClaimants", maxClaimants);
        if (assignees != null) body.put("assigneeIds", assignees);
        return objectMapper.writeValueAsString(body);
    }

    private int claimStatus(String taskId, Cookie cookie) {
        try {
            return mockMvc.perform(post("/api/v1/tasks/{id}/claim", taskId).cookie(cookie).with(csrf()))
                    .andReturn().getResponse().getStatus();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode createDirection(String title) throws Exception {
        return json(mockMvc.perform(post("/api/v1/content-directions").cookie(adminCookie).with(csrf())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", title, "description", title + "的管理员说明"))))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode submitContent(Cookie cookie, String directionId, String platform,
                                   LocalDate date, String topic, int sequence) throws Exception {
        return json(mockMvc.perform(post("/api/v1/content-posts").cookie(cookie).with(csrf())
                        .contentType("application/json")
                        .content(contentBody(directionId, platform, date, topic, sequence, true,
                                "https://example.com/mock-content/" + sequence)))
                .andExpect(status().isCreated()).andReturn());
    }

    private String contentBody(String directionId, String platform, LocalDate date, String topic,
                               int sequence, boolean original, String url) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "platform", platform, "publishDate", date.toString(), "directionId", directionId,
                "topic", topic, "contentUrl", url, "viewCount", 1000 + sequence,
                "likeCount", 100 + sequence, "favoriteCount", 20 + sequence, "originalDeclared", original));
    }

    private byte[] xlsxImportFixture() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("客户");
            var header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("手机号");
            header.createCell(2).setCellValue("年级");
            for (int index = 0; index < 5; index++) {
                var row = sheet.createRow(index + 1);
                row.createCell(0).setCellValue("XLSX客户-" + index);
                row.createCell(1).setCellValue("1550000000" + index);
                row.createCell(2).setCellValue("26级");
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] enrichmentFixture(String name, String major, String grade, String highSchool,
                                     String sourceLocation) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var source = workbook.createSheet("Sheet1");
            var sourceHeader = source.createRow(0);
            sourceHeader.createCell(0).setCellValue("姓名");
            sourceHeader.createCell(1).setCellValue("专业");
            sourceHeader.createCell(2).setCellValue("年级");
            sourceHeader.createCell(3).setCellValue("入学前单位");
            var sourceRow = source.createRow(1);
            sourceRow.createCell(0).setCellValue(name);
            sourceRow.createCell(1).setCellValue(major);
            sourceRow.createCell(2).setCellValue(grade);
            sourceRow.createCell(3).setCellValue(highSchool);
            var mapping = workbook.createSheet("所在地映射");
            var mappingHeader = mapping.createRow(0);
            mappingHeader.createCell(0).setCellValue("入学前单位");
            mappingHeader.createCell(1).setCellValue("所在地");
            mappingHeader.createCell(2).setCellValue("状态");
            var mappingRow = mapping.createRow(1);
            mappingRow.createCell(0).setCellValue(highSchool);
            mappingRow.createCell(1).setCellValue(sourceLocation);
            mappingRow.createCell(2).setCellValue("已匹配");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private JsonNode findBy(JsonNode array, String field, String value) {
        for (JsonNode item : array) if (value.equals(item.path(field).asText())) return item;
        throw new AssertionError("Missing item where " + field + "=" + value);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private record MemberAccount(String username, UUID membershipId, UUID teamId, String role, Cookie cookie) {}
}
