package com.yingge.cloud.feishu.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FeishuBitableClient {
    private static final URI OFFICIAL_ENDPOINT = URI.create("https://open.feishu.cn");
    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("\\\"code\\\"\\s*:\\s*(\\d{1,12})");
    private final RestClient rest;
    private final Map<UUID, CachedToken> tokens = new ConcurrentHashMap<>();
    private final Map<UUID, CachedToken> appTokens = new ConcurrentHashMap<>();

    @Autowired
    public FeishuBitableClient(@Value("${app.feishu.base-url:https://open.feishu.cn}") String baseUrl,
                               RestClient.Builder builders) {
        this(baseUrl, builders, true);
    }

    FeishuBitableClient(String baseUrl, RestClient.Builder builders, boolean applyTimeouts) {
        URI endpoint = validateEndpoint(baseUrl);
        RestClient.Builder configured = builders.clone().baseUrl(endpoint.toASCIIString());
        if (applyTimeouts) {
            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofSeconds(20));
            configured.requestFactory(factory);
        }
        this.rest = configured.build();
    }

    static URI validateEndpoint(String value) {
        URI endpoint;
        try {
            endpoint = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid Feishu endpoint", exception);
        }
        if (!OFFICIAL_ENDPOINT.getScheme().equalsIgnoreCase(endpoint.getScheme())
                || !OFFICIAL_ENDPOINT.getHost().equalsIgnoreCase(endpoint.getHost())
                || endpoint.getPort() != -1
                || (endpoint.getPath() != null && !endpoint.getPath().isBlank() && !"/".equals(endpoint.getPath()))
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalStateException("Feishu endpoint must be https://open.feishu.cn");
        }
        return OFFICIAL_ENDPOINT;
    }

    public void invalidateToken(UUID integrationId) {
        tokens.remove(integrationId);
        appTokens.remove(integrationId);
    }

    /**
     * Exchanges one browser authorization code for the short lived identity payload.
     * The returned user token is consumed immediately and is deliberately never cached.
     */
    public FeishuIdentity exchangeIdentity(Credentials credentials, String authorizationCode) {
        return exchangeAuthorization(credentials, authorizationCode).identity();
    }

    /**
     * Uses the Base owner's short-lived user token once to subscribe the configured Base. The
     * token never leaves this client, is never cached and is discarded before this method returns.
     */
    public FeishuIdentity authorizeRecordChangeSubscription(Credentials credentials, String authorizationCode,
                                                            String baseAppToken) {
        FeishuAuthorization authorization = exchangeAuthorization(credentials, authorizationCode);
        ensureRecordChangeSubscription(authorization.userAccessToken(), baseAppToken);
        return authorization.identity();
    }

    private FeishuAuthorization exchangeAuthorization(Credentials credentials, String authorizationCode) {
        String code = authorizationCode == null ? "" : authorizationCode.trim();
        if (!code.matches("[A-Za-z0-9._~-]{8,1024}")) {
            throw new FeishuClientException("FEISHU_AUTHORIZATION_CODE_INVALID");
        }
        JsonNode tokenResponse = rest.post()
                .uri("/open-apis/authen/v1/oidc/access_token")
                .header("Authorization", "Bearer " + appAccessToken(credentials, false))
                .body(Map.of("grant_type", "authorization_code", "code", code))
                .retrieve().body(JsonNode.class);
        JsonNode tokenData = requireSuccess(tokenResponse);
        String userToken = text(tokenData, "access_token");
        if (userToken == null) userToken = text(tokenResponse, "access_token");
        if (userToken == null) throw new FeishuClientException("FEISHU_USER_TOKEN_RESPONSE_INVALID");

        JsonNode userResponse = rest.get()
                .uri("/open-apis/authen/v1/user_info")
                .header("Authorization", "Bearer " + userToken)
                .retrieve().body(JsonNode.class);
        JsonNode user = requireSuccess(userResponse);
        String tenantKey = text(user, "tenant_key");
        String userId = text(user, "user_id");
        if (tenantKey == null || userId == null) {
            throw new FeishuClientException("FEISHU_IDENTITY_RESPONSE_INVALID");
        }
        return new FeishuAuthorization(
                new FeishuIdentity(tenantKey, userId, text(user, "open_id"), text(user, "union_id"), text(user, "name")),
                userToken);
    }

    public RemoteRecord getRecord(Credentials credentials, String baseAppToken, String tableId, String recordId) {
        JsonNode response = rest.get()
                .uri("/open-apis/bitable/v1/apps/{appToken}/tables/{tableId}/records/{recordId}",
                        safeId(baseAppToken), safeId(tableId), safeId(recordId))
                .header("Authorization", "Bearer " + accessToken(credentials, false))
                .retrieve().body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        JsonNode record = data.has("record") ? data.path("record") : data;
        String id = text(record, "record_id");
        JsonNode fields = record.path("fields");
        if (id == null || !fields.isObject()) throw new FeishuClientException("FEISHU_RESPONSE_INVALID");
        return new RemoteRecord(id, fields.deepCopy());
    }

    public void testBase(Credentials credentials, String baseAppToken) {
        String token = accessToken(credentials, true);
        JsonNode response = rest.get()
                .uri("/open-apis/bitable/v1/apps/{appToken}/tables?page_size=1", safeId(baseAppToken))
                .header("Authorization", "Bearer " + token)
                .retrieve().body(JsonNode.class);
        requireSuccess(response);
    }

    /**
     * Cloud-document events are scoped to an explicitly subscribed file. Merely registering the
     * event type in the developer console and opening a long connection does not subscribe a Base.
     */
    public EventSubscription ensureRecordChangeSubscription(Credentials credentials, String baseAppToken) {
        return ensureRecordChangeSubscription(accessToken(credentials, false), baseAppToken);
    }

    private EventSubscription ensureRecordChangeSubscription(String bearerToken, String baseAppToken) {
        String fileToken = safeId(baseAppToken);
        JsonNode statusResponse = rest.get()
                .uri(builder -> builder
                        .path("/open-apis/drive/v1/files/{fileToken}/get_subscribe")
                        .queryParam("file_type", "bitable")
                        .build(fileToken))
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve().body(JsonNode.class);
        JsonNode status = requireSuccess(statusResponse);
        if (status.path("is_subscribe").asBoolean(false)) {
            return new EventSubscription(true, false);
        }

        JsonNode subscribeResponse = rest.post()
                .uri(builder -> builder
                        .path("/open-apis/drive/v1/files/{fileToken}/subscribe")
                        .queryParam("file_type", "bitable")
                        .build(fileToken))
                .header("Authorization", "Bearer " + bearerToken)
                .retrieve().body(JsonNode.class);
        requireSuccess(subscribeResponse);
        return new EventSubscription(true, true);
    }

    public static String safeFailureCode(RuntimeException exception) {
        if (exception instanceof FeishuClientException clientError) return clientError.code();
        if (exception instanceof ResourceAccessException) return "FEISHU_NETWORK_ERROR";
        if (exception instanceof HttpStatusCodeException http) {
            Matcher code = ERROR_CODE_PATTERN.matcher(http.getResponseBodyAsString());
            if (code.find() && !"0".equals(code.group(1))) return "FEISHU_BUSINESS_" + code.group(1);
            return "FEISHU_HTTP_" + http.getStatusCode().value();
        }
        return "FEISHU_REQUEST_REJECTED";
    }

    public CreatedTable createTable(Credentials credentials, String baseAppToken, String name,
                                    List<FieldDefinition> fields) {
        List<Map<String, Object>> remoteFields = fields.stream()
                .map(field -> Map.<String, Object>of("field_name", field.name(), "type", field.type()))
                .toList();
        JsonNode response = rest.post()
                .uri("/open-apis/bitable/v1/apps/{appToken}/tables", safeId(baseAppToken))
                .header("Authorization", "Bearer " + accessToken(credentials, false))
                .body(Map.of("table", Map.of(
                        "name", name,
                        "default_view_name", "全部客户",
                        "fields", remoteFields
                )))
                .retrieve().body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        String tableId = text(data, "table_id");
        if (tableId == null) tableId = text(data.path("table"), "table_id");
        if (tableId == null) throw new FeishuClientException("FEISHU_RESPONSE_INVALID");
        return new CreatedTable(tableId);
    }

    public List<RemoteField> listFields(Credentials credentials, String baseAppToken, String tableId) {
        JsonNode response = rest.get()
                .uri("/open-apis/bitable/v1/apps/{appToken}/tables/{tableId}/fields?page_size=100",
                        safeId(baseAppToken), safeId(tableId))
                .header("Authorization", "Bearer " + accessToken(credentials, false))
                .retrieve().body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        List<RemoteField> fields = new ArrayList<>();
        JsonNode items = data.path("items");
        if (items.isArray()) {
            for (JsonNode item : items) {
                String id = text(item, "field_id");
                String name = text(item, "field_name");
                if (id != null && name != null) fields.add(new RemoteField(id, name, item.path("type").asInt(-1)));
            }
        }
        return fields;
    }

    public RemoteField createField(Credentials credentials, String baseAppToken, String tableId,
                                   FieldDefinition field) {
        JsonNode response = rest.post()
                .uri("/open-apis/bitable/v1/apps/{appToken}/tables/{tableId}/fields",
                        safeId(baseAppToken), safeId(tableId))
                .header("Authorization", "Bearer " + accessToken(credentials, false))
                .body(Map.of("field_name", field.name(), "type", field.type()))
                .retrieve().body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        JsonNode created = data.has("field") ? data.path("field") : data;
        String id = text(created, "field_id");
        String name = text(created, "field_name");
        if (id == null || name == null) throw new FeishuClientException("FEISHU_RESPONSE_INVALID");
        return new RemoteField(id, name, created.path("type").asInt(field.type()));
    }

    public List<String> batchCreate(Credentials credentials, String baseAppToken, String tableId,
                                    List<Map<String, Object>> records) {
        List<Map<String, Object>> body = records.stream().map(fields -> Map.<String, Object>of("fields", fields)).toList();
        JsonNode response = rest.post()
                .uri("/open-apis/bitable/v1/apps/{appToken}/tables/{tableId}/records/batch_create",
                        safeId(baseAppToken), safeId(tableId))
                .header("Authorization", "Bearer " + accessToken(credentials, false))
                .body(Map.of("records", body))
                .retrieve().body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        List<String> ids = new ArrayList<>();
        JsonNode created = data.path("records");
        if (created.isArray()) {
            for (JsonNode item : created) {
                String id = text(item, "record_id");
                if (id == null) throw new FeishuClientException("FEISHU_RESPONSE_INVALID");
                ids.add(id);
            }
        }
        if (ids.size() != records.size()) throw new FeishuClientException("FEISHU_RESPONSE_INVALID");
        return ids;
    }

    public void batchUpdate(Credentials credentials, String baseAppToken, String tableId,
                            List<RecordUpdate> records) {
        List<Map<String, Object>> body = records.stream().map(record -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("record_id", safeId(record.recordId()));
            item.put("fields", record.fields());
            return item;
        }).toList();
        JsonNode response = rest.post()
                .uri("/open-apis/bitable/v1/apps/{appToken}/tables/{tableId}/records/batch_update",
                        safeId(baseAppToken), safeId(tableId))
                .header("Authorization", "Bearer " + accessToken(credentials, false))
                .body(Map.of("records", body))
                .retrieve().body(JsonNode.class);
        requireSuccess(response);
    }

    public BotInfo botInfo(Credentials credentials) {
        JsonNode response = rest.get()
                .uri("/open-apis/bot/v3/info")
                .header("Authorization", "Bearer " + accessToken(credentials, false))
                .retrieve().body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        // Feishu has returned the bot object both at the response root and below
        // `data` across API generations. Accept both envelope shapes so a
        // harmless upstream response change cannot disable group provisioning.
        JsonNode bot = response != null && response.has("bot") ? response.path("bot")
                : data.has("bot") ? data.path("bot") : data;
        String openId = text(bot, "open_id");
        if (openId == null) openId = text(bot, "bot_id");
        if (openId == null) throw new FeishuClientException("FEISHU_BOT_RESPONSE_INVALID");
        return new BotInfo(openId, text(bot, "app_name"), text(bot, "avatar_url"));
    }

    public List<ChatInfo> listChats(Credentials credentials) {
        List<ChatInfo> chats = new ArrayList<>();
        String pageToken = null;
        do {
            String current = pageToken;
            JsonNode response = rest.get()
                    .uri(builder -> {
                        var uri = builder.path("/open-apis/im/v1/chats")
                                .queryParam("user_id_type", "user_id")
                                .queryParam("page_size", 100);
                        if (current != null) uri.queryParam("page_token", current);
                        return uri.build();
                    })
                    .header("Authorization", "Bearer " + accessToken(credentials, false))
                    .retrieve().body(JsonNode.class);
            JsonNode data = requireSuccess(response);
            JsonNode items = data.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String chatId = text(item, "chat_id");
                    if (chatId != null) chats.add(new ChatInfo(chatId, text(item, "name"), text(item, "description")));
                }
            }
            pageToken = data.path("has_more").asBoolean(false) ? text(data, "page_token") : null;
        } while (pageToken != null && chats.size() < 1000);
        return chats;
    }

    public String sendChatMessage(Credentials credentials, String chatId, String messageType,
                                  String content, UUID messageUuid) {
        String type = messageType == null ? "" : messageType.trim();
        if (!List.of("text", "interactive").contains(type)) {
            throw new FeishuClientException("FEISHU_MESSAGE_TYPE_INVALID");
        }
        if (content == null || content.isBlank() || content.length() > 30000) {
            throw new FeishuClientException("FEISHU_MESSAGE_CONTENT_INVALID");
        }
        JsonNode response = rest.post()
                .uri(builder -> builder.path("/open-apis/im/v1/messages")
                        .queryParam("receive_id_type", "chat_id").build())
                .header("Authorization", "Bearer " + accessToken(credentials, false))
                .body(Map.of("receive_id", safeId(chatId), "msg_type", type,
                        "content", content, "uuid", messageUuid.toString()))
                .retrieve().body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        String messageId = text(data, "message_id");
        if (messageId == null && data.has("message")) messageId = text(data.path("message"), "message_id");
        if (messageId == null) throw new FeishuClientException("FEISHU_MESSAGE_RESPONSE_INVALID");
        return messageId;
    }

    private String accessToken(Credentials credentials, boolean forceRefresh) {
        if (!forceRefresh) {
            CachedToken current = tokens.get(credentials.integrationId());
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) return current.value();
        }
        JsonNode response = rest.post()
                .uri("/open-apis/auth/v3/tenant_access_token/internal")
                .body(Map.of("app_id", credentials.appId(), "app_secret", credentials.appSecret()))
                .retrieve().body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        String value = text(response, "tenant_access_token");
        if (value == null) value = text(data, "tenant_access_token");
        long expires = response == null ? 0 : response.path("expire").asLong(data.path("expire").asLong(0));
        if (value == null || expires <= 0) throw new FeishuClientException("FEISHU_TOKEN_RESPONSE_INVALID");
        tokens.put(credentials.integrationId(), new CachedToken(value, Instant.now().plusSeconds(expires)));
        return value;
    }

    private String appAccessToken(Credentials credentials, boolean forceRefresh) {
        if (!forceRefresh) {
            CachedToken current = appTokens.get(credentials.integrationId());
            if (current != null && current.expiresAt().isAfter(Instant.now().plusSeconds(60))) return current.value();
        }
        JsonNode response = rest.post()
                .uri("/open-apis/auth/v3/app_access_token/internal")
                .body(Map.of("app_id", credentials.appId(), "app_secret", credentials.appSecret()))
                .retrieve().body(JsonNode.class);
        JsonNode data = requireSuccess(response);
        String value = text(response, "app_access_token");
        if (value == null) value = text(data, "app_access_token");
        long expires = response == null ? 0 : response.path("expire").asLong(data.path("expire").asLong(0));
        if (value == null || expires <= 0) throw new FeishuClientException("FEISHU_APP_TOKEN_RESPONSE_INVALID");
        appTokens.put(credentials.integrationId(), new CachedToken(value, Instant.now().plusSeconds(expires)));
        return value;
    }

    private JsonNode requireSuccess(JsonNode response) {
        if (response == null || !response.isObject()) throw new FeishuClientException("FEISHU_RESPONSE_INVALID");
        int code = response.path("code").asInt(Integer.MIN_VALUE);
        if (code != 0) throw new FeishuClientException(code == Integer.MIN_VALUE
                ? "FEISHU_RESPONSE_INVALID" : "FEISHU_BUSINESS_" + code);
        return response.path("data");
    }

    private String safeId(String value) {
        String clean = value == null ? "" : value.trim();
        if (!clean.matches("[A-Za-z0-9_-]{3,256}")) throw new FeishuClientException("FEISHU_IDENTIFIER_INVALID");
        return clean;
    }

    private String text(JsonNode node, String key) {
        if (node == null || !node.hasNonNull(key) || !node.path(key).isTextual()) return null;
        String value = node.path(key).asText().trim();
        return value.isEmpty() ? null : value;
    }

    public record Credentials(UUID integrationId, String appId, String appSecret) {}
    public record FieldDefinition(String key, String name, int type) {}
    public record RemoteField(String id, String name, int type) {}
    public record CreatedTable(String tableId) {}
    public record RecordUpdate(String recordId, Map<String, Object> fields) {}
    public record RemoteRecord(String recordId, JsonNode fields) {}
    public record EventSubscription(boolean subscribed, boolean changed) {}
    public record BotInfo(String openId, String name, String avatarUrl) {}
    public record ChatInfo(String chatId, String name, String description) {}
    public record FeishuIdentity(String tenantKey, String userId, String openId, String unionId, String displayName) {}
    private record FeishuAuthorization(FeishuIdentity identity, String userAccessToken) {}
    private record CachedToken(String value, Instant expiresAt) {}

    public static final class FeishuClientException extends RuntimeException {
        private final String code;

        public FeishuClientException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
