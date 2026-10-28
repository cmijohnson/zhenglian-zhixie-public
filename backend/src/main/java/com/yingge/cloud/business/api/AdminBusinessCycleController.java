package com.yingge.cloud.business.api;

import com.yingge.cloud.business.application.BusinessCycleService;
import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.shared.api.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/business-cycles")
public class AdminBusinessCycleController {
    private final BusinessCycleService service;

    public AdminBusinessCycleController(BusinessCycleService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<BusinessCycleDtos.Response> search(
            @RequestParam(required = false) Boolean crossSale,
            @RequestParam(required = false) UUID primaryMembershipId,
            @RequestParam(required = false) UUID assistantMembershipId,
            @RequestParam(required = false) BusinessCycleStatus status,
            @RequestParam(required = false) Instant markedFrom,
            @RequestParam(required = false) Instant markedTo,
            @RequestParam(required = false) String quality,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.adminSearch(crossSale, primaryMembershipId, assistantMembershipId, status,
                markedFrom, markedTo, quality, page, size);
    }

    @PostMapping("/{cycleId}/win-confirmations")
    public BusinessCycleDtos.Response confirmWon(
            @PathVariable UUID cycleId,
            @Valid @RequestBody BusinessCycleDtos.ConfirmWonRequest body) {
        return service.confirmWon(cycleId, body);
    }

    @PostMapping("/{cycleId}/quote-confirmations")
    public BusinessCycleDtos.Response confirmQuote(
            @PathVariable UUID cycleId,
            @Valid @RequestBody BusinessCycleDtos.ConfirmQuoteRequest body) {
        return service.confirmQuote(cycleId, body);
    }

    @PostMapping("/{cycleId}/quote-rejections")
    public BusinessCycleDtos.Response rejectQuoteLock(
            @PathVariable UUID cycleId,
            @Valid @RequestBody BusinessCycleDtos.RejectQuoteLockRequest body) {
        return service.rejectQuoteLock(cycleId, body);
    }

    @GetMapping("/participant-change-requests")
    public List<BusinessCycleDtos.ParticipantChangeResponse> participantChanges() {
        return service.adminParticipantChanges();
    }

    @PostMapping("/{cycleId}/participant-change-requests")
    public BusinessCycleDtos.ParticipantChangeResponse requestParticipantChange(
            @PathVariable UUID cycleId,
            @Valid @RequestBody BusinessCycleDtos.ParticipantChangeRequest body) {
        return service.requestParticipantChange(cycleId, body);
    }

    @PostMapping("/{cycleId}/participant-change-requests/{requestId}/apply")
    public BusinessCycleDtos.Response applyParticipantChange(
            @PathVariable UUID cycleId, @PathVariable UUID requestId) {
        return service.applyParticipantChange(cycleId, requestId);
    }

    @PostMapping("/{cycleId}/participant-snapshots")
    public BusinessCycleDtos.Response reviseParticipants(
            @PathVariable UUID cycleId,
            @Valid @RequestBody BusinessCycleDtos.ReviseParticipantsRequest body) {
        return service.reviseParticipants(cycleId, body);
    }

    @PostMapping("/{cycleId}/corrections")
    public BusinessCycleDtos.Response correctStatus(
            @PathVariable UUID cycleId,
            @Valid @RequestBody BusinessCycleDtos.CorrectStatusRequest body) {
        return service.correctStatus(cycleId, body);
    }
}
