package com.yingge.cloud.customerpool.api;

import com.yingge.cloud.customerpool.application.CustomerPoolService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CustomerPoolController {
    private final CustomerPoolService service;

    public CustomerPoolController(CustomerPoolService service) {
        this.service = service;
    }

    @GetMapping("/customer-pools")
    public List<CustomerPoolDtos.PoolEntry> pools(
            @RequestParam(required = false) CustomerPoolService.PoolType type) {
        return service.list(type);
    }

    @PostMapping("/customer-pools/{entryId}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerPoolDtos.ClaimResult claim(@PathVariable UUID entryId,
                                               @Valid @RequestBody CustomerPoolDtos.ClaimRequest request) {
        return service.claim(entryId, request);
    }

    @PostMapping("/customers/{customerId}/release-to-pool")
    @ResponseStatus(HttpStatus.CREATED)
    public UUID release(@PathVariable UUID customerId,
                        @Valid @RequestBody CustomerPoolDtos.ReleaseRequest request) {
        return service.release(customerId, request);
    }

    @GetMapping("/second-line-recruitments")
    public List<CustomerPoolDtos.SecondLineRecruitment> secondLineRecruitments() {
        return service.openSecondLineRecruitments();
    }

    @PostMapping("/second-line-recruitments/{recruitmentId}/claim")
    public CustomerPoolDtos.SecondLineClaimResult claimSecondLine(@PathVariable UUID recruitmentId) {
        return service.claimSecondLine(recruitmentId);
    }

    @GetMapping("/customers/{customerId}/second-lines")
    public List<CustomerPoolDtos.SecondLineAssignment> secondLines(@PathVariable UUID customerId) {
        return service.secondLines(customerId);
    }

    @PostMapping("/customers/{customerId}/second-line-contact-authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerPoolDtos.ContactAuthorization requestContactAuthorization(
            @PathVariable UUID customerId,
            @Valid @RequestBody CustomerPoolDtos.ContactAuthorizationRequest request) {
        return service.requestContactAuthorization(customerId, request);
    }

    @PostMapping("/second-line-contact-authorizations/{authorizationId}/decision")
    public CustomerPoolDtos.ContactAuthorization decideContactAuthorization(
            @PathVariable UUID authorizationId,
            @Valid @RequestBody CustomerPoolDtos.ContactAuthorizationDecision decision) {
        return service.decideContactAuthorization(authorizationId, decision);
    }

    @PostMapping("/admin/customers/{customerId}/second-lines")
    @ResponseStatus(HttpStatus.CREATED)
    public void assignSecondLine(@PathVariable UUID customerId,
                                 @Valid @RequestBody CustomerPoolDtos.AssignSecondLineRequest request) {
        service.assignSecondLine(customerId, request.membershipId());
    }

    @DeleteMapping("/admin/customers/{customerId}/second-lines/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSecondLine(@PathVariable UUID customerId, @PathVariable UUID membershipId) {
        service.removeSecondLine(customerId, membershipId);
    }

    @PostMapping("/admin/customer-takeovers/{taskId}/resolve")
    public void resolveTakeover(@PathVariable UUID taskId,
                                @Valid @RequestBody CustomerPoolDtos.ResolveTakeoverRequest request) {
        service.resolveTakeover(taskId, request);
    }
}
