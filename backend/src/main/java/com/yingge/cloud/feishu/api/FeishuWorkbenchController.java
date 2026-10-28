package com.yingge.cloud.feishu.api;

import com.yingge.cloud.customer.api.CustomerDtos;
import com.yingge.cloud.feishu.application.FeishuDealWorkbenchService;
import com.yingge.cloud.feishu.application.FeishuWorkbenchService;
import com.yingge.cloud.interaction.api.InteractionDtos;
import com.yingge.cloud.shared.api.PageResponse;
import com.yingge.cloud.task.api.TaskDtos;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/feishu/workbench")
public class FeishuWorkbenchController {
    private final FeishuWorkbenchService service;
    private final FeishuDealWorkbenchService deals;

    public FeishuWorkbenchController(FeishuWorkbenchService service, FeishuDealWorkbenchService deals) {
        this.service = service;
        this.deals = deals;
    }

    @GetMapping("/bootstrap")
    public FeishuWorkbenchDtos.BootstrapResponse bootstrap() { return service.bootstrap(); }

    @GetMapping("/customers")
    public PageResponse<CustomerDtos.Response> customers(@RequestParam(required = false) String search,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "30") int size) {
        return service.customers(search, page, size);
    }

    @GetMapping("/customers/{customerId}")
    public CustomerDtos.Response customer(@PathVariable UUID customerId) { return service.customer(customerId); }

    @GetMapping("/customers/{customerId}/interactions")
    public List<InteractionDtos.Response> interactions(@PathVariable UUID customerId) {
        return service.interactions(customerId);
    }

    @PatchMapping("/customers/{customerId}/controlled-fields")
    public CustomerDtos.Response updateControlledFields(
            @PathVariable UUID customerId,
            @Valid @RequestBody FeishuWorkbenchDtos.ControlledCustomerUpdateRequest body) {
        return service.updateControlledFields(customerId, body);
    }

    @PostMapping("/customer-drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public FeishuWorkbenchDtos.DraftResponse draft(@Valid @RequestBody FeishuWorkbenchDtos.CustomerDraftRequest body) {
        return service.createDraft(body);
    }

    @PostMapping("/customer-drafts/{draftId}/confirm")
    public CustomerDtos.Response confirmDraft(@PathVariable UUID draftId,
                                               @Valid @RequestBody FeishuWorkbenchDtos.ConfirmDraftRequest body) {
        return service.confirmDraft(draftId, body);
    }

    @DeleteMapping("/customer-drafts/{draftId}")
    public ResponseEntity<Void> cancelDraft(@PathVariable UUID draftId) {
        service.cancelDraft(draftId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/customers/{customerId}/interactions")
    @ResponseStatus(HttpStatus.CREATED)
    public InteractionDtos.Response interaction(@PathVariable UUID customerId,
                                                @Valid @RequestBody InteractionDtos.CreateRequest body) {
        return service.addInteraction(customerId, body);
    }

    @GetMapping("/tasks")
    public List<TaskDtos.TaskResponse> tasks() { return service.taskList(); }

    @GetMapping("/tasks/{taskId}/assignments")
    public List<TaskDtos.AssignmentResponse> taskAssignments(@PathVariable UUID taskId) {
        return service.taskAssignments(taskId);
    }

    @PostMapping("/tasks/{taskId}/claim")
    public TaskDtos.AssignmentResponse claim(@PathVariable UUID taskId) { return service.claimTask(taskId); }

    @PatchMapping("/tasks/{taskId}/my-assignment")
    public TaskDtos.AssignmentResponse update(@PathVariable UUID taskId,
                                              @Valid @RequestBody TaskDtos.MemberUpdateRequest body) {
        return service.updateTask(taskId, body);
    }

    @PostMapping("/tasks/{taskId}/review")
    public TaskDtos.AssignmentResponse review(@PathVariable UUID taskId,
                                              @Valid @RequestBody FeishuWorkbenchDtos.TaskReviewRequest body) {
        return service.reviewTask(taskId, body);
    }

    @PostMapping("/tasks/{taskId}/close")
    public ResponseEntity<Void> close(@PathVariable UUID taskId) {
        service.closeTask(taskId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/members")
    public List<FeishuWorkbenchDtos.DealMemberResponse> members(
            @RequestParam(required = false) String search) {
        return deals.members(search);
    }

    @GetMapping("/customers/{customerId}/collaboration")
    public CustomerDtos.CollaborationOverview collaboration(@PathVariable UUID customerId) {
        return deals.collaboration(customerId);
    }

    @PostMapping("/customers/{customerId}/transfer")
    public CustomerDtos.Response transfer(@PathVariable UUID customerId,
                                           @Valid @RequestBody CustomerDtos.TransferOwnershipRequest body) {
        return deals.transfer(customerId, body);
    }

    @GetMapping("/deal-drafts/current")
    public ResponseEntity<FeishuWorkbenchDtos.DealDraftResponse> currentDealDraft() {
        FeishuWorkbenchDtos.DealDraftResponse draft = deals.currentDraft();
        return draft == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(draft);
    }

    @PostMapping("/deal-drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public FeishuWorkbenchDtos.DealDraftResponse createDealDraft(
            @Valid @RequestBody FeishuWorkbenchDtos.DealDraftCreateRequest body) {
        return deals.createDraft(body.customerId());
    }

    @PutMapping("/deal-drafts/{draftId}")
    public FeishuWorkbenchDtos.DealDraftResponse updateDealDraft(
            @PathVariable UUID draftId,
            @Valid @RequestBody FeishuWorkbenchDtos.DealDraftUpdateRequest body) {
        return deals.updateDraft(draftId, body);
    }

    @DeleteMapping("/deal-drafts/{draftId}")
    public ResponseEntity<Void> cancelDealDraft(@PathVariable UUID draftId) {
        deals.cancelDraft(draftId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/deal-drafts/{draftId}/confirm")
    public FeishuWorkbenchDtos.DealConfirmationResult confirmDealDraft(
            @PathVariable UUID draftId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody FeishuWorkbenchDtos.DealDraftConfirmRequest body) {
        return deals.confirm(draftId, body.expectedVersion(), idempotencyKey);
    }

    @GetMapping("/deal-confirmations/{requestId}")
    public FeishuWorkbenchDtos.DealConfirmationResult dealConfirmation(@PathVariable UUID requestId) {
        return deals.confirmation(requestId);
    }

    @GetMapping("/deal-leaderboard")
    public FeishuDealBotDtos.LeaderboardResponse dealLeaderboard(
            @RequestParam(required = false) String month) {
        return deals.leaderboard(month);
    }
}
