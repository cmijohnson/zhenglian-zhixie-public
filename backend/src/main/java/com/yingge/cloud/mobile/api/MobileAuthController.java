package com.yingge.cloud.mobile.api;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.auth.infrastructure.CloudPrincipal;
import com.yingge.cloud.auth.application.RegistrationService;
import com.yingge.cloud.mobile.application.MobileAuthService;
import com.yingge.cloud.mobile.security.MobileAuthenticationAttributes;
import com.yingge.cloud.shared.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mobile/auth")
public class MobileAuthController {
    private final AuthenticationManager authenticationManager;
    private final MobileAuthService service;
    private final CurrentActorProvider actors;
    private final RegistrationService registrations;

    public MobileAuthController(AuthenticationManager authenticationManager, MobileAuthService service,
                                CurrentActorProvider actors, RegistrationService registrations) {
        this.authenticationManager = authenticationManager;
        this.service = service;
        this.actors = actors;
        this.registrations = registrations;
    }

    @PostMapping("/registration-options")
    public RegistrationService.RegistrationOptions registrationOptions(
            @Valid @RequestBody MobileDtos.RegistrationOptionsRequest body) {
        return registrations.registrationOptions(body.registrationCode());
    }

    @PostMapping("/register")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public RegistrationService.RegistrationResponse register(
            @Valid @RequestBody MobileDtos.RegisterRequest body) {
        return registrations.register(new RegistrationService.RegisterRequest(
                body.username(), body.realName(), body.password(), body.registrationCode(), body.teamId()));
    }

    @PostMapping("/login")
    public MobileDtos.TokenResponse login(
            @RequestHeader("X-Yingge-App-Version") String appVersion,
            @RequestHeader("X-Yingge-Version-Code") long versionCode,
            @RequestHeader("X-Yingge-Platform") MobileDtos.Platform platform,
            @RequestHeader("X-Yingge-Device-Id") UUID installationId,
            @Valid @RequestBody MobileDtos.LoginRequest body) {
        try {
            var authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
            return service.login((CloudPrincipal) authentication.getPrincipal(), installationId, platform,
                    appVersion, versionCode, body.deviceName());
        } catch (AuthenticationException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误");
        }
    }

    @PostMapping("/refresh")
    public MobileDtos.TokenResponse refresh(
            @RequestHeader("X-Yingge-App-Version") String appVersion,
            @RequestHeader("X-Yingge-Version-Code") long versionCode,
            @RequestHeader("X-Yingge-Platform") MobileDtos.Platform platform,
            @RequestHeader("X-Yingge-Device-Id") UUID installationId,
            @Valid @RequestBody MobileDtos.RefreshRequest body) {
        return service.refresh(body.refreshToken(), installationId, platform, appVersion, versionCode);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        service.logout(sessionId(request));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/devices")
    public List<MobileDtos.Device> devices(HttpServletRequest request) {
        return service.devices(actors.require().userId(), sessionId(request));
    }

    @DeleteMapping("/devices/{deviceId}")
    public ResponseEntity<Void> revokeDevice(@PathVariable UUID deviceId) {
        service.revokeDevice(actors.require().userId(), deviceId);
        return ResponseEntity.noContent().build();
    }

    private UUID sessionId(HttpServletRequest request) {
        Object value = request.getAttribute(MobileAuthenticationAttributes.SESSION_ID);
        if (value instanceof UUID id) return id;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "MOBILE_SESSION_REQUIRED", "该操作仅支持移动设备会话");
    }
}
