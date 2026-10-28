package com.yingge.cloud.consultation.api;

import com.yingge.cloud.consultation.application.ConsultationService;
import com.yingge.cloud.assistant.api.AssistantDtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ConsultationController {
    private final ConsultationService service;

    public ConsultationController(ConsultationService service) {
        this.service = service;
    }

    @GetMapping("/consultations")
    public List<ConsultationDtos.Response> list() { return service.list(); }

    @GetMapping("/consultations/{id}")
    public ConsultationDtos.Response get(@PathVariable UUID id) { return service.get(id); }

    @GetMapping("/consultations/candidates")
    public List<ConsultationDtos.ConsultantCandidate> candidates() { return service.candidates(); }

    @GetMapping("/consultations/draft")
    public AssistantDtos.ConsultationDraft draft(@RequestParam UUID customerId) { return service.draft(customerId); }

    @GetMapping("/consultation-settings")
    public ConsultationDtos.Settings publicSettings() { return service.settings(); }

    @PostMapping("/consultations")
    public ConsultationDtos.Response create(@Valid @RequestBody ConsultationDtos.CreateRequest request) {
        return service.create(request);
    }

    @PostMapping("/consultations/{id}/accept")
    public ConsultationDtos.Response accept(@PathVariable UUID id,
                                             @Valid @RequestBody ConsultationDtos.VersionRequest request) {
        return service.accept(id, request);
    }

    @PostMapping("/consultations/{id}/reject")
    public ConsultationDtos.Response reject(@PathVariable UUID id,
                                             @Valid @RequestBody ConsultationDtos.RejectRequest request) {
        return service.reject(id, request);
    }

    @PostMapping("/consultations/{id}/cancel")
    public ConsultationDtos.Response cancel(@PathVariable UUID id,
                                             @Valid @RequestBody ConsultationDtos.CancelRequest request) {
        return service.cancel(id, request);
    }

    @PostMapping("/consultations/{id}/end")
    public ConsultationDtos.Response end(@PathVariable UUID id,
                                          @Valid @RequestBody ConsultationDtos.EndRequest request) {
        return service.end(id, request);
    }

    @PostMapping("/consultations/{id}/complete")
    public ConsultationDtos.Response complete(@PathVariable UUID id,
                                               @Valid @RequestBody ConsultationDtos.CompleteRequest request) {
        return service.complete(id, request);
    }

    @GetMapping("/admin/consultation-settings")
    public ConsultationDtos.Settings settings() { return service.adminSettings(); }

    @PutMapping("/admin/consultation-settings")
    public ConsultationDtos.Settings updateSettings(@Valid @RequestBody ConsultationDtos.UpdateSettings request) {
        return service.updateSettings(request);
    }

    @GetMapping("/consultation-failure-reasons")
    public List<ConsultationDtos.FailureReason> failureReasons(
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.failureReasons(includeInactive);
    }

    @PostMapping("/admin/consultation-reasons")
    public ConsultationDtos.FailureReason createFailureReason(
            @Valid @RequestBody ConsultationDtos.SaveFailureReason request) {
        return service.saveFailureReason(null, request);
    }

    @PutMapping("/admin/consultation-reasons/{id}")
    public ConsultationDtos.FailureReason updateFailureReason(
            @PathVariable UUID id, @Valid @RequestBody ConsultationDtos.SaveFailureReason request) {
        return service.saveFailureReason(id, request);
    }
}
