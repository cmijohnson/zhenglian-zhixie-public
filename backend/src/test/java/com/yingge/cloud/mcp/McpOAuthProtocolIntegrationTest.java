package com.yingge.cloud.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class McpOAuthProtocolIntegrationTest {
    private static final String PASSWORD = "Mcp-Admin-Password-2026";
    private static final String RESOURCE = "http://localhost/mcp";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("yingge_mcp_test").withUsername("yingge_test").withPassword("test-password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.bootstrap.admin-username", () -> "mcp-admin");
        registry.add("app.bootstrap.admin-password", () -> PASSWORD);
        registry.add("app.bootstrap.admin-display-name", () -> "MCP 测试管理员");
        registry.add("app.mcp.issuer", () -> "http://localhost");
        registry.add("app.mcp.resource-uri", () -> RESOURCE);
        registry.add("app.mcp.allowed-hosts", () -> "localhost:*");
        registry.add("app.mcp.allowed-origins", () -> "http://localhost:*");
        registry.add("app.ai-admin.master-key", () -> Base64.getEncoder()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test
    void oauthPkceDynamicToolsIdempotencyApprovalRevocationAndRefreshReplayWork() throws Exception {
        Cookie browserSession = login();

        mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"client_name":"bad-scheme-client","redirect_uris":["ftp://127.0.0.1/callback"]}
                """)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_redirect_uri"));

        mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"client_name":"private-http-client",
                 "redirect_uris":["http://127.0.0.1:8088/api/mcp/oauth/callback"]}
                """)).andExpect(status().isCreated());

        mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"client_name":"private-https-client","redirect_uris":["https://127.0.0.1/callback"]}
                """)).andExpect(status().isCreated());

        mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"client_name":"QwenPaw MCP Client",
                 "redirect_uris":["http://127.0.0.1:8088/api/mcp/oauth/callback"],
                 "grant_types":["authorization_code","refresh_token"],
                 "response_types":["code"],"token_endpoint_auth_method":"none"}
                """)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.redirect_uris[0]")
                        .value("http://127.0.0.1:8088/api/mcp/oauth/callback"));

        JsonNode client = json(mockMvc.perform(post("/oauth2/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"client_name":"MCP integration client","redirect_uris":["http://127.0.0.1:43123/callback"],
                 "grant_types":["authorization_code","refresh_token"],"response_types":["code"],
                 "token_endpoint_auth_method":"none"}
                """)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String clientId = client.get("client_id").asText();

        Token grantOne = authorize(browserSession, clientId, "yingge.read yingge.write yingge.ai yingge.admin");
        mockMvc.perform(get("/api/v1/agent/authorizations").cookie(browserSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(grantOne.grantId()))
                .andExpect(jsonPath("$[0].clientName").value("MCP integration client"));
        mockMvc.perform(get("/api/v1/admin/agent/authorizations").cookie(browserSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(grantOne.grantId()))
                .andExpect(jsonPath("$[0].displayName").value("MCP 测试管理员"));
        HttpResponse<String> initialize = initialize(grantOne.accessToken());
        assertThat(initialize.statusCode()).isEqualTo(200);
        assertThat(mapper.readTree(initialize.body()).at("/result/protocolVersion").asText()).isEqualTo("2025-11-25");
        String sessionId = initialize.headers().firstValue("mcp-session-id").orElseThrow();

        JsonNode listed = mcp(grantOne.accessToken(), sessionId, 2, "tools/list", Map.of());
        assertThat(listed.at("/result/tools").findValuesAsText("name"))
                .contains("yingge_customer_create", "yingge_admin_members", "yingge_admin_ai_settings_update");

        Map<String, Object> createArgs = Map.of(
                "request", Map.of("fullName", "MCP 幂等客户", "phone", "13800001234", "visibility", "PRIVATE"),
                "idempotencyKey", "customer-create-1");
        JsonNode created = mcp(grantOne.accessToken(), sessionId, 3, "tools/call",
                Map.of("name", "yingge_customer_create", "arguments", createArgs));
        String customerId = created.at("/result/structuredContent/data/id").asText();
        assertThat(customerId).isNotBlank();
        JsonNode retried = mcp(grantOne.accessToken(), sessionId, 4, "tools/call",
                Map.of("name", "yingge_customer_create", "arguments", createArgs));
        assertThat(retried.at("/result/structuredContent/data/id").asText()).isEqualTo(customerId);
        assertThat(jdbc.queryForObject("select count(*) from customers where full_name='MCP 幂等客户'", Long.class))
                .isEqualTo(1);

        JsonNode forged = mcp(grantOne.accessToken(), sessionId, 5, "tools/call", Map.of("name", "yingge_customer_get",
                "arguments", Map.of("customerId", customerId, "organizationId", UUID.randomUUID().toString())));
        assertThat(forged.at("/result/structuredContent/error/code").asText()).isEqualTo("UNKNOWN_ARGUMENT");

        Map<String, Object> archiveBase = Map.of("customerId", customerId, "idempotencyKey", "archive-1");
        JsonNode approvalRequired = mcp(grantOne.accessToken(), sessionId, 6, "tools/call",
                Map.of("name", "yingge_customer_archive", "arguments", archiveBase));
        assertThat(approvalRequired.at("/result/structuredContent/approvalRequired").asBoolean()).isTrue();
        String approvalId = approvalRequired.at("/result/structuredContent/approval/approvalId").asText();
        mockMvc.perform(post("/api/v1/agent/approvals/{id}/approve", approvalId)
                        .cookie(browserSession).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        Map<String, Object> archiveApproved = Map.of("customerId", customerId, "idempotencyKey", "archive-1",
                "approvalId", approvalId);
        JsonNode archived = mcp(grantOne.accessToken(), sessionId, 7, "tools/call",
                Map.of("name", "yingge_customer_archive", "arguments", archiveApproved));
        assertThat(archived.at("/result/structuredContent/data/completed").asBoolean()).isTrue();
        JsonNode archiveRetry = mcp(grantOne.accessToken(), sessionId, 8, "tools/call",
                Map.of("name", "yingge_customer_archive", "arguments", archiveApproved));
        assertThat(archiveRetry.at("/result/structuredContent/data/completed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select archived_at is not null from customers where id=?", Boolean.class,
                UUID.fromString(customerId))).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='MCP_TOOL_CALL'", Long.class))
                .isGreaterThanOrEqualTo(6);

        mockMvc.perform(delete("/api/v1/agent/authorizations/{id}", grantOne.grantId())
                        .cookie(browserSession).with(csrf())).andExpect(status().isNoContent());
        assertThat(initialize(grantOne.accessToken()).statusCode()).isEqualTo(401);

        Token readOnlyAdmin = authorize(browserSession, clientId, "yingge.read yingge.admin.read");
        HttpResponse<String> readonlyInitialize = initialize(readOnlyAdmin.accessToken());
        assertThat(readonlyInitialize.statusCode()).isEqualTo(200);
        String readonlySession = readonlyInitialize.headers().firstValue("mcp-session-id").orElseThrow();
        JsonNode readonlyTools = mcp(readOnlyAdmin.accessToken(), readonlySession, 20, "tools/list", Map.of());
        assertThat(readonlyTools.at("/result/tools").findValuesAsText("name"))
                .contains("yingge_identity_me", "yingge_leadership_dashboard_get", "yingge_admin_members")
                .doesNotContain("yingge_customer_create", "yingge_admin_member_create", "yingge_admin_team_create");
        JsonNode identity = mcp(readOnlyAdmin.accessToken(), readonlySession, 21, "tools/call",
                Map.of("name", "yingge_identity_me", "arguments", Map.of()));
        assertThat(identity.at("/result/structuredContent/data/userId").asText()).isNotBlank();
        assertThat(identity.at("/result/structuredContent/data/membershipId").asText()).isNotBlank();
        assertThat(identity.at("/result/structuredContent/data/organizationId").asText()).isNotBlank();
        JsonNode members = mcp(readOnlyAdmin.accessToken(), readonlySession, 22, "tools/call",
                Map.of("name", "yingge_admin_members", "arguments", Map.of()));
        assertThat(members.at("/result/structuredContent/ok").asBoolean()).isTrue();
        mockMvc.perform(delete("/api/v1/agent/authorizations/{id}", readOnlyAdmin.grantId())
                        .cookie(browserSession).with(csrf())).andExpect(status().isNoContent());

        Token grantTwo = authorize(browserSession, clientId, "yingge.read");
        JsonNode rotated = json(mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form(Map.of("grant_type", "refresh_token", "client_id", clientId,
                                "refresh_token", grantTwo.refreshToken()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String rotatedAccess = rotated.get("access_token").asText();
        mockMvc.perform(post("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form(Map.of("grant_type", "refresh_token", "client_id", clientId,
                                "refresh_token", grantTwo.refreshToken()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        assertThat(initialize(rotatedAccess).statusCode()).isEqualTo(401);
    }

    @Test
    void personalTokenWorksWithoutOAuthCallbackAndRotationRevokesThePreviousToken() throws Exception {
        Cookie browserSession = login();

        JsonNode first = json(mockMvc.perform(post("/api/v1/agent/personal-token")
                        .cookie(browserSession).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value("yingge_qwenpaw_personal"))
                .andExpect(jsonPath("$.resource").value(RESOURCE))
                .andExpect(jsonPath("$.scopes").isArray())
                .andReturn().getResponse().getContentAsString());
        String firstToken = first.get("accessToken").asText();
        String firstGrantId = first.get("grantId").asText();
        assertThat(firstToken).isNotBlank();
        assertThat(jdbc.queryForObject("select token_hash from oauth_access_tokens where grant_id=?",
                String.class, UUID.fromString(firstGrantId))).hasSize(64).isNotEqualTo(firstToken);

        HttpResponse<String> initialized = initialize(firstToken);
        assertThat(initialized.statusCode()).isEqualTo(200);
        String sessionId = initialized.headers().firstValue("mcp-session-id").orElseThrow();
        JsonNode tools = mcp(firstToken, sessionId, 2, "tools/list", Map.of());
        assertThat(tools.at("/result/tools").findValuesAsText("name"))
                .contains("yingge_customer_search", "yingge_admin_members");

        JsonNode second = json(mockMvc.perform(post("/api/v1/agent/personal-token")
                        .cookie(browserSession).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String secondToken = second.get("accessToken").asText();
        assertThat(secondToken).isNotEqualTo(firstToken);
        assertThat(initialize(firstToken).statusCode()).isEqualTo(401);
        assertThat(initialize(secondToken).statusCode()).isEqualTo(200);

        mockMvc.perform(delete("/api/v1/agent/authorizations/{id}", second.get("grantId").asText())
                        .cookie(browserSession).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(initialize(secondToken).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action='MCP_PERSONAL_TOKEN_ISSUED'",
                Long.class)).isGreaterThanOrEqualTo(2);
    }

    private Token authorize(Cookie session, String clientId, String scopes) throws Exception {
        String verifier = "mcp-integration-verifier-abcdefghijklmnopqrstuvwxyz-0123456789";
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        var authorization = mockMvc.perform(get("/oauth2/authorize")
                        .param("response_type", "code").param("client_id", clientId)
                        .param("redirect_uri", "http://127.0.0.1:43123/callback")
                        .param("scope", scopes).param("state", "state-1")
                        .param("code_challenge", challenge).param("code_challenge_method", "S256")
                        .param("resource", RESOURCE))
                .andExpect(status().is3xxRedirection()).andReturn();
        String consentRedirect = authorization.getResponse().getRedirectedUrl();
        assertThat(consentRedirect).startsWith("http://localhost/oauth/authorize?request=");
        String requestId = java.util.Arrays.stream(URI.create(consentRedirect).getRawQuery().split("&"))
                .filter(value -> value.startsWith("request="))
                .findFirst().orElseThrow().substring("request=".length());
        mockMvc.perform(get("/api/v1/oauth/requests/{id}", requestId).cookie(session))
                .andExpect(status().isOk());
        JsonNode decision = json(mockMvc.perform(post("/api/v1/oauth/requests/{id}/approve", requestId)
                        .cookie(session).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        URI redirect = URI.create(decision.get("redirectUrl").asText());
        String code = java.util.Arrays.stream(redirect.getRawQuery().split("&"))
                .filter(value -> value.startsWith("code=")).findFirst().orElseThrow().substring(5);
        JsonNode token = json(mockMvc.perform(post("/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content(form(Map.of("grant_type", "authorization_code", "client_id", clientId,
                                "code", java.net.URLDecoder.decode(code, StandardCharsets.UTF_8),
                                "redirect_uri", "http://127.0.0.1:43123/callback", "code_verifier", verifier))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        return new Token(token.get("access_token").asText(), token.get("refresh_token").asText(),
                decision.get("grantId").asText());
    }

    private HttpResponse<String> initialize(String token) throws Exception {
        return http(token, null, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                 "capabilities":{},"clientInfo":{"name":"integration-test","version":"1.0"}}}
                """);
    }

    private JsonNode mcp(String token, String sessionId, int id, String method, Object params) throws Exception {
        String body = mapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        HttpResponse<String> response = http(token, sessionId, body);
        assertThat(response.statusCode()).isEqualTo(200);
        String data = response.body().lines().filter(line -> line.startsWith("data:"))
                .map(line -> line.substring(5).trim()).findFirst().orElseThrow();
        return mapper.readTree(data);
    }

    private HttpResponse<String> http(String token, String sessionId, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/mcp"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        if (sessionId != null) request.header("mcp-session-id", sessionId);
        return HttpClient.newHttpClient().send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Cookie login() throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", "mcp-admin", "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getCookie("YINGGE_SESSION");
    }

    private String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8)
                + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    private JsonNode json(String value) throws Exception { return mapper.readTree(value); }
    private record Token(String accessToken, String refreshToken, String grantId) {}
}
