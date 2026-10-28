package com.yingge.cloud.feishu.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class FeishuBitableClientContractTest {
    private static final UUID INTEGRATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void obtainsTenantTokenAndTestsOnlyTheOfficialBitableBase() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/bascnExample123/tables?page_size=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"success\",\"data\":{\"items\":[]}}",
                        MediaType.APPLICATION_JSON));

        client.testBase(credentials(), "bascnExample123");
        server.verify();
    }

    @Test
    void createsTableAndBatchesCreateAndUpdateWithoutSendingSecretAsBearer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/bascnExample123/tables"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andExpect(content().json("""
                        {"table":{"name":"英格云客户档案","default_view_name":"全部客户",
                        "fields":[{"field_name":"英格云客户ID","type":1}]}}
                        """))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("app-secret"))))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"table_id\":\"tblExample123\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/bascnExample123/tables/tblExample123/records/batch_create"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andExpect(content().json("{\"records\":[{\"fields\":{\"姓名\":\"测试客户\"}}]}"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"records\":[{\"record_id\":\"recExample123\"}]}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/bascnExample123/tables/tblExample123/records/batch_update"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andExpect(content().json("""
                        {"records":[{"record_id":"recExample123","fields":{"姓名":"更新客户"}}]}
                        """))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"records\":[]}}", MediaType.APPLICATION_JSON));

        assertThat(client.createTable(credentials(), "bascnExample123", "英格云客户档案",
                List.of(new FeishuBitableClient.FieldDefinition("customerId", "英格云客户ID", 1))).tableId())
                .isEqualTo("tblExample123");
        assertThat(client.batchCreate(credentials(), "bascnExample123", "tblExample123",
                List.of(Map.of("姓名", "测试客户")))).containsExactly("recExample123");
        client.batchUpdate(credentials(), "bascnExample123", "tblExample123",
                List.of(new FeishuBitableClient.RecordUpdate("recExample123", Map.of("姓名", "更新客户"))));
        server.verify();
    }

    @Test
    void listsAndCreatesFieldsUsingStableFieldIds() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/bascnExample123/tables/tblExample123/fields?page_size=100"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"items":[{"field_id":"fldCustomer","field_name":"客户编号","type":1}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/bascnExample123/tables/tblExample123/fields"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andExpect(content().json("{\"field_name\":\"是否归档\",\"type\":7}"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"field":{"field_id":"fldArchived","field_name":"是否归档","type":7}}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.listFields(credentials(), "bascnExample123", "tblExample123"))
                .containsExactly(new FeishuBitableClient.RemoteField("fldCustomer", "客户编号", 1));
        assertThat(client.createField(credentials(), "bascnExample123", "tblExample123",
                new FeishuBitableClient.FieldDefinition("archived", "是否归档", 7)))
                .isEqualTo(new FeishuBitableClient.RemoteField("fldArchived", "是否归档", 7));
        server.verify();
    }

    @Test
    void treatsHttp200BusinessErrorAsFailureAndRejectsNonOfficialEndpoints() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);
        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                .andRespond(withSuccess("{\"code\":99991672,\"msg\":\"invalid app secret\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.testBase(credentials(), "bascnExample123"))
                .isInstanceOf(FeishuBitableClient.FeishuClientException.class)
                .hasMessage("FEISHU_BUSINESS_99991672");
        assertThatThrownBy(() -> FeishuBitableClient.validateEndpoint("http://open.feishu.cn"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> FeishuBitableClient.validateEndpoint("https://example.com"))
                .isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void exchangesAuthorizationCodeWithoutCachingOrLeakingTheUserToken() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"app_id\":\"cli_example\",\"app_secret\":\"app-secret\"}"))
                .andRespond(withSuccess("""
                        {"code":0,"app_access_token":"app-token","expire":7200}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/oidc/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer app-token"))
                .andExpect(content().json("{\"grant_type\":\"authorization_code\",\"code\":\"auth-code-123\"}"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("app-secret"))))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"access_token":"short-user-token"}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/user_info"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer short-user-token"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"tenant_key":"tenant-1","user_id":"user-1",
                        "open_id":"open-1","union_id":"union-1","name":"测试成员"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.exchangeIdentity(credentials(), "auth-code-123"))
                .isEqualTo(new FeishuBitableClient.FeishuIdentity(
                        "tenant-1", "user-1", "open-1", "union-1", "测试成员"));
        server.verify();
    }

    @Test
    void usesTheOwnersUserTokenOnceToSubscribeWithoutPersistingIt() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal"))
                .andRespond(withSuccess("{\"code\":0,\"app_access_token\":\"app-token\",\"expire\":7200}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/oidc/access_token"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"access_token\":\"short-user-token\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/authen/v1/user_info"))
                .andExpect(header("Authorization", "Bearer short-user-token"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"tenant_key\":\"tenant-1\",\"user_id\":\"user-1\"}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/drive/v1/files/bascnExample123/get_subscribe?file_type=bitable"))
                .andExpect(header("Authorization", "Bearer short-user-token"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"is_subscribe\":false}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/drive/v1/files/bascnExample123/subscribe?file_type=bitable"))
                .andExpect(header("Authorization", "Bearer short-user-token"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{}}", MediaType.APPLICATION_JSON));

        assertThat(client.authorizeRecordChangeSubscription(credentials(), "auth-code-123", "bascnExample123"))
                .extracting(FeishuBitableClient.FeishuIdentity::tenantKey,
                        FeishuBitableClient.FeishuIdentity::userId)
                .containsExactly("tenant-1", "user-1");
        server.verify();
    }

    @Test
    void readsOneRemoteRecordThroughTheTenantCredential() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/bitable/v1/apps/bascnExample123/tables/tblExample123/records/recExample123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"record":{"record_id":"recExample123","fields":{"昵称":"小英"}}}}
                        """, MediaType.APPLICATION_JSON));

        FeishuBitableClient.RemoteRecord record = client.getRecord(
                credentials(), "bascnExample123", "tblExample123", "recExample123");
        assertThat(record.recordId()).isEqualTo("recExample123");
        assertThat(record.fields().path("昵称").asText()).isEqualTo("小英");
        server.verify();
    }

    @Test
    void subscribesTheConfiguredBitableToRecordChangeEventsWhenNeeded() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/drive/v1/files/bascnExample123/get_subscribe"
                        + "?file_type=bitable"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"is_subscribe\":false}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/drive/v1/files/bascnExample123/subscribe"
                        + "?file_type=bitable"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"success\",\"data\":{}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.ensureRecordChangeSubscription(credentials(), "bascnExample123"))
                .isEqualTo(new FeishuBitableClient.EventSubscription(true, true));
        server.verify();
    }

    @Test
    void doesNotResubscribeAnAlreadySubscribedBitable() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/drive/v1/files/bascnExample123/get_subscribe"
                        + "?file_type=bitable"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"is_subscribe\":true}}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.ensureRecordChangeSubscription(credentials(), "bascnExample123"))
                .isEqualTo(new FeishuBitableClient.EventSubscription(true, false));
        server.verify();
    }

    @Test
    void reportsAStableBusinessErrorWhenFileSubscriptionIsRejected() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/drive/v1/files/bascnExample123/get_subscribe"
                        + "?file_type=bitable"))
                .andRespond(withSuccess("{\"code\":1069302,\"msg\":\"forbidden\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.ensureRecordChangeSubscription(credentials(), "bascnExample123"))
                .isInstanceOf(FeishuBitableClient.FeishuClientException.class)
                .hasMessage("FEISHU_BUSINESS_1069302");
        server.verify();
    }

    @Test
    void mapsTransportFailuresWithoutLeakingResponseDetails() {
        assertThat(FeishuBitableClient.safeFailureCode(
                HttpClientErrorException.create(org.springframework.http.HttpStatus.FORBIDDEN,
                        "Forbidden", org.springframework.http.HttpHeaders.EMPTY, new byte[0], null)))
                .isEqualTo("FEISHU_HTTP_403");
        assertThat(FeishuBitableClient.safeFailureCode(
                HttpClientErrorException.create(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Bad Request", org.springframework.http.HttpHeaders.EMPTY,
                        "{\"code\":1069302,\"msg\":\"sensitive detail\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo("FEISHU_BUSINESS_1069302");
        assertThat(FeishuBitableClient.safeFailureCode(new IllegalStateException("sensitive detail")))
                .isEqualTo("FEISHU_REQUEST_REJECTED");
    }

    @Test
    void listsBotChatsAndSendsAnIdempotentChatMessage() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);
        UUID messageUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");

        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/bot/v3/info"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"bot":{"open_id":"ou_bot","app_name":"英格云"}}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/im/v1/chats?user_id_type=user_id&page_size=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"has_more":false,"items":[
                          {"chat_id":"oc_submit","name":"成单提交"},
                          {"chat_id":"oc_broadcast","name":"全员广播"}]}}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=chat_id"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tenant-token"))
                .andExpect(content().json("""
                        {"receive_id":"oc_submit","msg_type":"text","content":"{\\\"text\\\":\\\"测试\\\"}",
                         "uuid":"22222222-2222-2222-2222-222222222222"}
                        """))
                .andRespond(withSuccess("""
                        {"code":0,"data":{"message_id":"om_message"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.botInfo(credentials()).name()).isEqualTo("英格云");
        assertThat(client.listChats(credentials())).extracting(FeishuBitableClient.ChatInfo::name)
                .containsExactly("成单提交", "全员广播");
        assertThat(client.sendChatMessage(credentials(), "oc_submit", "text", "{\"text\":\"测试\"}", messageUuid))
                .isEqualTo("om_message");
        server.verify();
    }

    @Test
    void acceptsBotInfoAtResponseRoot() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FeishuBitableClient client = new FeishuBitableClient("https://open.feishu.cn", builder, false);

        expectToken(server);
        server.expect(requestTo("https://open.feishu.cn/open-apis/bot/v3/info"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":0,"msg":"ok","bot":{"open_id":"ou_root_bot","app_name":"英格云"}}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.botInfo(credentials()))
                .isEqualTo(new FeishuBitableClient.BotInfo("ou_root_bot", "英格云", null));
        server.verify();
    }

    private void expectToken(MockRestServiceServer server) {
        server.expect(requestTo("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"app_id\":\"cli_example\",\"app_secret\":\"app-secret\"}"))
                .andRespond(withSuccess("{\"code\":0,\"msg\":\"success\",\"tenant_access_token\":\"tenant-token\",\"expire\":7200}",
                        MediaType.APPLICATION_JSON));
    }

    private FeishuBitableClient.Credentials credentials() {
        return new FeishuBitableClient.Credentials(INTEGRATION_ID, "cli_example", "app-secret");
    }
}
