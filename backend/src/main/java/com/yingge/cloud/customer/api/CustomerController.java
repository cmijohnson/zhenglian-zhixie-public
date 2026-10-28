package com.yingge.cloud.customer.api;

import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.shared.api.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {
    private final CustomerService service;

    public CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<CustomerDtos.Response> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String intention,
            @RequestParam(required = false) String verification,
            @RequestParam(required = false) String grade,
            @RequestParam(required = false) String school,
            @RequestParam(required = false) List<UUID> tagIds,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) UUID membershipId,
            @RequestParam(required = false) UUID ownerMembershipId,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(defaultValue = "updatedDesc") String sort
    ) {
        return service.search(search, status, intention, verification, grade, school,
                tagIds == null ? List.of() : tagIds, includeArchived, teamId, membershipId,
                ownerMembershipId, page, size, sort);
    }

    @GetMapping("/{id}")
    public CustomerDtos.Response get(@PathVariable UUID id) {
        return service.get(id);
    }

    @GetMapping("/{id}/collaboration")
    public CustomerDtos.CollaborationOverview collaboration(@PathVariable UUID id) {
        return service.collaboration(id);
    }

    @PutMapping("/{id}/collaborators")
    public CustomerDtos.CollaborationOverview replaceCollaborators(
            @PathVariable UUID id, @Valid @RequestBody CustomerDtos.UpdateCollaboratorsRequest request) {
        return service.replaceCollaborators(id, request);
    }

    @PostMapping("/{id}/transfer")
    public CustomerDtos.Response transferOwnership(
            @PathVariable UUID id, @Valid @RequestBody CustomerDtos.TransferOwnershipRequest request) {
        return service.transferOwnership(id, request);
    }

    @PostMapping("/duplicate-check")
    public CustomerDtos.DuplicateResponse duplicate(@Valid @RequestBody CustomerDtos.DuplicateRequest request) {
        return service.duplicateCheck(request);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerDtos.Response create(@Valid @RequestBody CustomerDtos.UpsertRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public CustomerDtos.Response update(@PathVariable UUID id, @Valid @RequestBody CustomerDtos.UpsertRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        service.archive(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/lead-review")
    public CustomerDtos.Response reviewLead(@PathVariable UUID id,
                                            @Valid @RequestBody CustomerDtos.LeadReviewRequest request) {
        return service.reviewLead(id, request);
    }
}
