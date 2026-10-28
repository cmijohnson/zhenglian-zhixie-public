package com.yingge.cloud.organization.api;

import com.yingge.cloud.organization.application.OrganizationAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class OrganizationAdminController {
    private final OrganizationAdminService service;

    public OrganizationAdminController(OrganizationAdminService service) {
        this.service = service;
    }

    @GetMapping("/members")
    public List<OrganizationAdminService.MemberResponse> members() { return service.members(); }

    @PostMapping("/members")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationAdminService.MemberResponse createMember(@Valid @RequestBody CreateMemberBody body) {
        return service.createMember(new OrganizationAdminService.CreateMemberRequest(
                body.username(), body.displayName(), body.initialPassword(), body.role(), body.teamId()));
    }

    @PatchMapping("/members/{membershipId}")
    public OrganizationAdminService.MemberResponse updateMember(
            @PathVariable UUID membershipId, @Valid @RequestBody UpdateMemberBody body) {
        return service.updateMember(membershipId, new OrganizationAdminService.UpdateMemberRequest(
                body.displayName(), body.role(), body.status(), body.teamId()));
    }

    @PostMapping("/members/{membershipId}/delete")
    public ResponseEntity<Void> deleteMember(@PathVariable UUID membershipId,
                                             @RequestBody(required = false) DeleteMemberBody body) {
        service.deleteMember(membershipId, body == null ? null : body.transferCustomerToMembershipId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/teams")
    public List<OrganizationAdminService.TeamResponse> teams() { return service.teams(); }

    @PostMapping("/teams")
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationAdminService.TeamResponse createTeam(@Valid @RequestBody CreateTeamBody body) {
        return service.createTeam(body.name());
    }

    @PostMapping("/teams/{teamId}/members")
    public ResponseEntity<Void> assign(@PathVariable UUID teamId, @Valid @RequestBody AssignTeamBody body) {
        service.assignTeam(teamId, body.membershipId(), body.manager());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/teams/{teamId}/leader")
    public OrganizationAdminService.TeamResponse assignLeader(
            @PathVariable UUID teamId, @Valid @RequestBody AssignLeaderBody body) {
        return service.assignLeader(teamId, body.membershipId());
    }

    @DeleteMapping("/teams/{teamId}")
    public ResponseEntity<Void> deleteTeam(@PathVariable UUID teamId) {
        service.deleteTeam(teamId);
        return ResponseEntity.noContent().build();
    }

    public record CreateMemberBody(@NotBlank @Size(max = 100) String username,
                                   @NotBlank @Size(max = 120) String displayName,
                                   @NotBlank @Size(max = 200) String initialPassword,
                                   @NotBlank String role, UUID teamId) {}
    public record CreateTeamBody(@NotBlank @Size(max = 120) String name) {}
    public record AssignTeamBody(UUID membershipId, boolean manager) {}
    public record AssignLeaderBody(@jakarta.validation.constraints.NotNull UUID membershipId) {}
    public record UpdateMemberBody(@NotBlank @Size(max = 120) String displayName,
                                   @NotBlank String role, @NotBlank String status, UUID teamId) {}
    public record DeleteMemberBody(UUID transferCustomerToMembershipId) {}
}
