package com.yingge.cloud.mobile.api;

import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.mobile.application.MobileWebSessionService;
import com.yingge.cloud.mobile.security.MobileAuthenticationAttributes;
import com.yingge.cloud.shared.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mobile/web-sessions")
public class MobileWebSessionController {
    private final MobileWebSessionService service;
    private final CurrentActorProvider actors;
    private final HttpSessionSecurityContextRepository contexts;

    public MobileWebSessionController(MobileWebSessionService service, CurrentActorProvider actors,
                                      HttpSessionSecurityContextRepository contexts) {
        this.service = service;
        this.actors = actors;
        this.contexts = contexts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MobileDtos.WebSessionTicketResponse create(HttpServletRequest request,
                                                       @Valid @RequestBody MobileDtos.WebSessionTicketRequest body) {
        var actor = actors.require();
        return service.create(sessionId(request), actor.membershipId(), actor.organizationId(), body.target());
    }

    @PostMapping(path = "/exchange", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<Void> exchange(@RequestParam String ticket, HttpServletRequest request,
                                         HttpServletResponse response) {
        MobileWebSessionService.Exchange exchange = service.exchange(ticket);
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                exchange.principal(), null, exchange.principal().getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        HttpSession session = request.getSession(true);
        request.changeSessionId();
        session = request.getSession(false);
        session.setMaxInactiveInterval(10 * 60);
        session.setAttribute(MobileAuthenticationAttributes.WEB_TARGET_SESSION, exchange.target());
        session.setAttribute(MobileAuthenticationAttributes.WEB_SOURCE_SESSION, exchange.sessionId());
        contexts.saveContext(context, request, response);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(exchange.targetPath()))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    private UUID sessionId(HttpServletRequest request) {
        Object value = request.getAttribute(MobileAuthenticationAttributes.SESSION_ID);
        if (value instanceof UUID id) return id;
        throw new ApiException(HttpStatus.UNAUTHORIZED, "MOBILE_SESSION_REQUIRED", "该操作仅支持移动设备会话");
    }
}
