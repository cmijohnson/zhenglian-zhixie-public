package com.yingge.cloud.auth.api;

import com.yingge.cloud.auth.application.RegistrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/auth")
public class RegistrationController {
    private final RegistrationService registrations;

    public RegistrationController(RegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationService.RegistrationResponse register(@Valid @RequestBody RegisterBody body) {
        return registrations.register(new RegistrationService.RegisterRequest(
                body.username(), body.realName(), body.password(), body.registrationCode(), body.teamId()));
    }

    @PostMapping("/registration-options")
    public RegistrationService.RegistrationOptions registrationOptions(
            @Valid @RequestBody RegistrationOptionsBody body) {
        return registrations.registrationOptions(body.registrationCode());
    }

    public record RegisterBody(
            @NotBlank @Size(max = 100) String username,
            @NotBlank @Size(max = 120) String realName,
            @NotBlank @Size(max = 200) String password,
            @NotBlank @Size(max = 100) String registrationCode,
            @NotNull UUID teamId
    ) {}

    public record RegistrationOptionsBody(@NotBlank @Size(max = 100) String registrationCode) {}
}
