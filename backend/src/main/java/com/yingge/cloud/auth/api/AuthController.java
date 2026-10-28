package com.yingge.cloud.auth.api;

import com.yingge.cloud.auth.application.AuthService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.shared.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository contextRepository;
    private final CurrentActorProvider actors;
    private final AuthService authService;

    public AuthController(
            AuthenticationManager authenticationManager,
            HttpSessionSecurityContextRepository contextRepository,
            CurrentActorProvider actors,
            AuthService authService
    ) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.actors = actors;
        this.authService = authService;
    }

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    @PostMapping("/login")
    public MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        final org.springframework.security.core.Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password())
            );
        } catch (AuthenticationException exception) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS", "用户名或密码错误");
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        authService.recordLogin();
        return me();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me() {
        CloudPrincipal principal = actors.requirePrincipal();
        var actor = principal.actor();
        return new MeResponse(
                actor.userId().toString(), actor.membershipId().toString(), actor.organizationId().toString(),
                actor.username(), actor.displayName(), actor.roles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                principal.passwordChangeRequired()
        );
    }

    @PostMapping("/password")
    public ResponseEntity<Void> password(@Valid @RequestBody PasswordRequest body) {
        authService.changePassword(body.currentPassword(), body.newPassword());
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}
    public record PasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(max = 200) String newPassword) {}
    public record CsrfResponse(String token, String headerName) {}
    public record MeResponse(
            String userId, String membershipId, String organizationId, String username, String displayName,
            Set<String> roles, boolean passwordChangeRequired
    ) {}
}
