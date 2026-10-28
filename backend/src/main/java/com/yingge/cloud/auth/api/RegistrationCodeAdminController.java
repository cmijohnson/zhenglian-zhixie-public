package com.yingge.cloud.auth.api;

import com.yingge.cloud.auth.application.RegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/registration-codes")
public class RegistrationCodeAdminController {
    private final RegistrationService registrations;

    public RegistrationCodeAdminController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    @GetMapping
    public List<RegistrationService.CodeResponse> list() {
        return registrations.listCodes();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationService.CreatedCodeResponse create(@Valid @RequestBody CreateCodeBody body) {
        return registrations.createCode(body.maxUses(), body.expiresAt());
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        registrations.disableCode(id);
        return ResponseEntity.noContent().build();
    }

    public record CreateCodeBody(@Min(1) @Max(1000) Integer maxUses, Instant expiresAt) {}
}
