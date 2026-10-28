package com.yingge.cloud.mcp;

import com.yingge.cloud.mcp.oauth.McpOAuthController;
import com.yingge.cloud.mcp.oauth.McpOAuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpOAuthControllerTest {
    @Mock McpOAuthService oauth;

    @Test
    void authorizationRedirectUsesAbsoluteIssuerUrl() throws Exception {
        UUID requestId = UUID.fromString("1ecf4915-c9e8-4e10-8f12-c1b635ac7833");
        when(oauth.beginAuthorization(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(requestId);
        McpProperties properties = new McpProperties(
                true,
                "http://101.43.22.42:38080/",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0
        );
        McpOAuthController controller = new McpOAuthController(oauth, properties);
        MockHttpServletResponse response = new MockHttpServletResponse();

        controller.authorize("code", "client", "http://127.0.0.1/callback", "yingge.read", "state",
                "challenge", "S256", "http://101.43.22.42:38080/mcp", response);

        assertThat(response.getRedirectedUrl()).isEqualTo(
                "http://101.43.22.42:38080/oauth/authorize?request=" + requestId);
    }
}
