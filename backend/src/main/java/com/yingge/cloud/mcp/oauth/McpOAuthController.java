package com.yingge.cloud.mcp.oauth;

import com.yingge.cloud.mcp.McpProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class McpOAuthController {
    private final McpOAuthService oauth;
    private final McpProperties properties;

    public McpOAuthController(McpOAuthService oauth, McpProperties properties) {
        this.oauth = oauth;
        this.properties = properties;
    }

    @GetMapping("/.well-known/oauth-protected-resource/mcp")
    public Map<String, Object> protectedResourceMetadata() {
        return Map.of(
                "resource", properties.normalizedResourceUri(),
                "authorization_servers", List.of(properties.normalizedIssuer()),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", McpOAuthService.SUPPORTED_SCOPES
        );
    }

    @GetMapping("/.well-known/oauth-authorization-server")
    public Map<String, Object> authorizationServerMetadata() {
        String issuer = properties.normalizedIssuer();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issuer", issuer);
        result.put("authorization_endpoint", issuer + "/oauth2/authorize");
        result.put("token_endpoint", issuer + "/oauth2/token");
        result.put("registration_endpoint", issuer + "/oauth2/register");
        result.put("revocation_endpoint", issuer + "/oauth2/revoke");
        result.put("response_types_supported", List.of("code"));
        result.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        result.put("code_challenge_methods_supported", List.of("S256"));
        result.put("token_endpoint_auth_methods_supported", List.of("none"));
        result.put("scopes_supported", McpOAuthService.SUPPORTED_SCOPES);
        return result;
    }

    @PostMapping(value = "/oauth2/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> register(@RequestBody DynamicRegistrationBody body) {
        var result = oauth.register(new McpOAuthService.ClientRegistrationRequest(
                body.clientName(), body.redirectUris(), body.grantTypes(), body.responseTypes(),
                body.tokenEndpointAuthMethod()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", result.clientId());
        response.put("client_name", result.clientName());
        response.put("redirect_uris", result.redirectUris());
        response.put("grant_types", result.grantTypes());
        response.put("response_types", result.responseTypes());
        response.put("token_endpoint_auth_method", result.tokenEndpointAuthMethod());
        response.put("client_id_issued_at", result.clientIdIssuedAt());
        return ResponseEntity.status(201).body(response);
    }

    @GetMapping("/oauth2/authorize")
    public void authorize(
            @RequestParam(name = "response_type") String responseType,
            @RequestParam(name = "client_id") String clientId,
            @RequestParam(name = "redirect_uri") String redirectUri,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String state,
            @RequestParam(name = "code_challenge") String codeChallenge,
            @RequestParam(name = "code_challenge_method") String codeChallengeMethod,
            @RequestParam(required = false) String resource,
            HttpServletResponse response
    ) throws IOException {
        UUID requestId = oauth.beginAuthorization(responseType, clientId, redirectUri, scope, state,
                codeChallenge, codeChallengeMethod, resource);
        response.sendRedirect(properties.normalizedIssuer() + "/oauth/authorize?request=" + requestId);
    }

    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam MultiValueMap<String, String> form) {
        Map<String, String> values = form.toSingleValueMap();
        var result = oauth.token(values);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", result.accessToken());
        response.put("token_type", result.tokenType());
        response.put("expires_in", result.expiresIn());
        response.put("refresh_token", result.refreshToken());
        response.put("scope", result.scope());
        response.put("resource", result.resource());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .body(response);
    }

    @PostMapping(value = "/oauth2/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> revoke(@RequestParam String client_id, @RequestParam String token) {
        oauth.revokeToken(client_id, token);
        return ResponseEntity.ok().build();
    }

    public record DynamicRegistrationBody(
            @com.fasterxml.jackson.annotation.JsonProperty("client_name") String clientName,
            @com.fasterxml.jackson.annotation.JsonProperty("redirect_uris") List<String> redirectUris,
            @com.fasterxml.jackson.annotation.JsonProperty("grant_types") List<String> grantTypes,
            @com.fasterxml.jackson.annotation.JsonProperty("response_types") List<String> responseTypes,
            @com.fasterxml.jackson.annotation.JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod
    ) {}
}
