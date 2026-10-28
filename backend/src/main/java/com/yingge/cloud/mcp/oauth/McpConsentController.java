package com.yingge.cloud.mcp.oauth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class McpConsentController {
    private final McpOAuthService oauth;

    public McpConsentController(McpOAuthService oauth) {
        this.oauth = oauth;
    }

    @GetMapping("/oauth/requests/{id}")
    public McpOAuthService.ConsentView consent(@PathVariable UUID id) {
        return oauth.consent(id);
    }

    @PostMapping("/oauth/requests/{id}/approve")
    public McpOAuthService.AuthorizationDecision approve(@PathVariable UUID id) {
        return oauth.approve(id);
    }

    @PostMapping("/oauth/requests/{id}/deny")
    public McpOAuthService.AuthorizationDecision deny(@PathVariable UUID id) {
        return oauth.deny(id);
    }

    @GetMapping("/agent/authorizations")
    public List<McpOAuthService.GrantView> ownGrants() {
        return oauth.ownGrants();
    }

    @PostMapping("/agent/personal-token")
    public McpOAuthService.PersonalToken issuePersonalToken() {
        return oauth.issuePersonalToken();
    }

    @DeleteMapping("/agent/authorizations/{id}")
    public ResponseEntity<Void> revokeOwn(@PathVariable UUID id) {
        oauth.revokeGrant(id, false);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/agent/authorizations")
    public List<McpOAuthService.GrantView> organizationGrants() {
        return oauth.organizationGrants();
    }

    @DeleteMapping("/admin/agent/authorizations/{id}")
    public ResponseEntity<Void> revokeOrganization(@PathVariable UUID id) {
        oauth.revokeGrant(id, true);
        return ResponseEntity.noContent().build();
    }
}
