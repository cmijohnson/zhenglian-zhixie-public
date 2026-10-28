package com.yingge.cloud.business.api;

import com.yingge.cloud.business.application.BusinessCycleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customers/{customerId}")
public class BusinessCycleController {
    private final BusinessCycleService service;

    public BusinessCycleController(BusinessCycleService service) {
        this.service = service;
    }

    @GetMapping("/business-cycles")
    public List<BusinessCycleDtos.Response> list(@PathVariable UUID customerId) {
        return service.list(customerId);
    }

    @GetMapping("/business-cycles/{cycleId}")
    public BusinessCycleDtos.Response get(@PathVariable UUID customerId, @PathVariable UUID cycleId) {
        return service.get(customerId, cycleId);
    }

    @PostMapping("/business-cycles")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessCycleDtos.Response create(@PathVariable UUID customerId,
                                             @Valid @RequestBody BusinessCycleDtos.CreateRequest body) {
        return service.create(customerId, body);
    }

    @PostMapping("/business-cycles/{cycleId}/won-declarations")
    public BusinessCycleDtos.Response declareWon(@PathVariable UUID customerId, @PathVariable UUID cycleId,
                                                  @Valid @RequestBody BusinessCycleDtos.DeclareWonRequest body) {
        return service.declareWon(customerId, cycleId, body);
    }

    @PostMapping("/business-cycles/{cycleId}/participant-lock-requests")
    public BusinessCycleDtos.Response requestParticipantLock(
            @PathVariable UUID customerId, @PathVariable UUID cycleId,
            @Valid @RequestBody BusinessCycleDtos.LockParticipantsRequest body) {
        return service.requestParticipantLock(customerId, cycleId, body);
    }

    @GetMapping("/business-cycles/{cycleId}/participant-change-requests")
    public List<BusinessCycleDtos.ParticipantChangeResponse> participantChanges(
            @PathVariable UUID customerId, @PathVariable UUID cycleId) {
        return service.participantChanges(customerId, cycleId);
    }

    @PostMapping("/business-cycles/{cycleId}/participant-change-requests/{requestId}/confirm")
    public BusinessCycleDtos.ParticipantChangeResponse confirmParticipantChange(
            @PathVariable UUID customerId, @PathVariable UUID cycleId, @PathVariable UUID requestId) {
        return service.confirmParticipantChange(customerId, cycleId, requestId);
    }

    @PostMapping("/business-cycles/{cycleId}/lost-to-competitor")
    public BusinessCycleDtos.Response markLost(@PathVariable UUID customerId, @PathVariable UUID cycleId,
                                               @Valid @RequestBody BusinessCycleDtos.MarkLostRequest body) {
        return service.markLost(customerId, cycleId, body);
    }

    @PostMapping("/business-cycles/{cycleId}/cancel")
    public BusinessCycleDtos.Response cancel(@PathVariable UUID customerId, @PathVariable UUID cycleId,
                                             @Valid @RequestBody BusinessCycleDtos.CancelRequest body) {
        return service.cancel(customerId, cycleId, body);
    }

    @GetMapping("/referrals")
    public List<BusinessCycleDtos.ReferralResponse> referrals(@PathVariable UUID customerId) {
        return service.listReferrals(customerId);
    }

    @PostMapping("/referrals")
    @ResponseStatus(HttpStatus.CREATED)
    public BusinessCycleDtos.ReferralResponse createReferral(
            @PathVariable UUID customerId,
            @Valid @RequestBody BusinessCycleDtos.CreateReferralRequest body) {
        return service.createReferral(customerId, body);
    }
}
