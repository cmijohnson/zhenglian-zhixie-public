package com.yingge.cloud.feishu.api;

import com.yingge.cloud.auth.api.AuthController;
import com.yingge.cloud.auth.application.AuthService;
import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.feishu.application.FeishuAuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth/feishu")
public class FeishuAuthController {
    private final FeishuAuthService service;
    private final HttpSessionSecurityContextRepository contexts;
    private final AuthService auth;

    public FeishuAuthController(FeishuAuthService service, HttpSessionSecurityContextRepository contexts,
                                AuthService auth) {
        this.service = service;
        this.contexts = contexts;
        this.auth = auth;
    }

    @GetMapping("/options")
    public FeishuAuthDtos.LoginOptionsResponse options() {
        return service.options();
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start(@RequestParam(defaultValue = "LOGIN") String purpose,
                                      @RequestParam(required = false) UUID integrationId,
                                      @RequestParam(required = false) String returnTo) {
        FeishuAuthService.StartResult result = service.start(purpose, integrationId, returnTo);
        return ResponseEntity.status(302).location(URI.create(result.authorizeUrl())).build();
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam String state, @RequestParam String code,
                                         HttpServletRequest request, HttpServletResponse response) {
        FeishuAuthService.CallbackResult result = service.callback(state, code);
        ResponseEntity.BodyBuilder redirect = ResponseEntity.status(302).location(URI.create(result.redirectTo()));
        if (result.principal() != null) {
            establishSession(result.principal(), request, response, true);
        }
        if (result.confirmationToken() != null) {
            redirect.header(HttpHeaders.SET_COOKIE, confirmationCookie(result.confirmationToken(), request.isSecure()).toString());
        }
        return redirect.build();
    }

    @PostMapping("/binding/confirm")
    public AuthController.MeResponse confirm(@Valid @RequestBody FeishuAuthDtos.ConfirmRequest body,
                                             HttpServletRequest request, HttpServletResponse response) {
        String confirmation = cookie(request, FeishuAuthService.CONFIRMATION_COOKIE);
        CloudPrincipal principal = service.confirm(body.intentId(), confirmation, body.username(), body.password());
        establishSession(principal, request, response, true);
        response.addHeader(HttpHeaders.SET_COOKIE, clearConfirmationCookie(request.isSecure()).toString());
        return me(principal);
    }

    @GetMapping("/binding")
    public FeishuAuthDtos.BindingResponse binding() {
        return service.binding();
    }

    @DeleteMapping("/binding")
    public ResponseEntity<Void> unbind(@Valid @RequestBody FeishuAuthDtos.UnbindRequest body) {
        service.unbind(body.currentPassword());
        return ResponseEntity.noContent().build();
    }

    private void establishSession(CloudPrincipal principal, HttpServletRequest request,
                                  HttpServletResponse response, boolean recordLogin) {
        if (request.getSession(false) != null) request.changeSessionId();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, principal.getPassword(), principal.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        if (recordLogin) auth.recordLogin();
    }

    private AuthController.MeResponse me(CloudPrincipal principal) {
        var actor = principal.actor();
        return new AuthController.MeResponse(actor.userId().toString(), actor.membershipId().toString(),
                actor.organizationId().toString(), actor.username(), actor.displayName(),
                actor.roles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                principal.passwordChangeRequired());
    }

    private String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) if (name.equals(cookie.getName())) return cookie.getValue();
        return null;
    }

    private ResponseCookie confirmationCookie(String value, boolean secure) {
        return ResponseCookie.from(FeishuAuthService.CONFIRMATION_COOKIE, value)
                .httpOnly(true).secure(secure).sameSite("Lax")
                .path("/api/v1/auth/feishu/binding/confirm").maxAge(Duration.ofMinutes(5)).build();
    }

    private ResponseCookie clearConfirmationCookie(boolean secure) {
        return ResponseCookie.from(FeishuAuthService.CONFIRMATION_COOKIE, "")
                .httpOnly(true).secure(secure).sameSite("Lax")
                .path("/api/v1/auth/feishu/binding/confirm").maxAge(Duration.ZERO).build();
    }
}
