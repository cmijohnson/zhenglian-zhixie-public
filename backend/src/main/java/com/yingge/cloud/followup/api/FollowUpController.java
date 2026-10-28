package com.yingge.cloud.followup.api;

import com.yingge.cloud.followup.application.CustomerFollowUpService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class FollowUpController {
    private final CustomerFollowUpService service;

    public FollowUpController(CustomerFollowUpService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/follow-up-recruitments")
    public List<FollowUpDtos.Recruitment> recruitments() {
        return service.openRecruitments();
    }

    @PostMapping("/api/v1/follow-up-recruitments/{id}/claim")
    public FollowUpDtos.ClaimResult claim(@PathVariable UUID id) {
        return service.claim(id);
    }

    @GetMapping("/api/v1/admin/follow-up-policy")
    public FollowUpDtos.Policy policy() {
        return service.policy();
    }

    @PutMapping("/api/v1/admin/follow-up-policy")
    public FollowUpDtos.Policy updatePolicy(@Valid @RequestBody FollowUpDtos.UpdatePolicy request) {
        return service.updatePolicy(request);
    }
}
