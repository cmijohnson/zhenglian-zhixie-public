package com.yingge.cloud.mcp.api;

import com.yingge.cloud.mcp.application.McpApprovalService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/agent/approvals")
public class McpApprovalController {
    private final McpApprovalService approvals;

    public McpApprovalController(McpApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<McpApprovalService.ApprovalView> list() { return approvals.mine(); }

    @GetMapping("/{id}")
    public McpApprovalService.ApprovalView get(@PathVariable UUID id) { return approvals.get(id); }

    @PostMapping("/{id}/approve")
    public McpApprovalService.ApprovalView approve(@PathVariable UUID id) { return approvals.decide(id, true); }

    @PostMapping("/{id}/reject")
    public McpApprovalService.ApprovalView reject(@PathVariable UUID id) { return approvals.decide(id, false); }
}
