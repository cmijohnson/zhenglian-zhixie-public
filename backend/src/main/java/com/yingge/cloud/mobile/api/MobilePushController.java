package com.yingge.cloud.mobile.api;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.mobile.application.MobilePushService;
import com.yingge.cloud.mobile.security.MobileAuthenticationAttributes;
import com.yingge.cloud.shared.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mobile/devices")
public class MobilePushController {
    private final MobilePushService service;
    private final CurrentActorProvider actors;

    public MobilePushController(MobilePushService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/push-registration")
    public ResponseEntity<Void> register(HttpServletRequest request,
                                         @Valid @RequestBody MobileDtos.PushRegistrationRequest body) {
        service.register(sessionId(request), body);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/push-registration")
    public ResponseEntity<Void> unregister(HttpServletRequest request) {
        service.unregister(sessionId(request));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/notification-preferences")
    public MobileDtos.NotificationPreferences preferences(
            @Valid @RequestBody MobileDtos.NotificationPreferences body) {
        return service.preferences(actors.require().membershipId(), body);
    }

    private UUID sessionId(HttpServletRequest request) {
        Object value = request.getAttribute(MobileAuthenticationAttributes.SESSION_ID);
        if (value instanceof UUID id) return id;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "MOBILE_SESSION_REQUIRED", "该操作仅支持移动设备会话");
    }
}
