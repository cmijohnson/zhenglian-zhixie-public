package com.yingge.cloud.audit.api;

import com.yingge.cloud.audit.application.AuditQueryService;
import com.yingge.cloud.shared.api.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/audit")
public class AuditController {
    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AuditResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.list(page, size, customerId, memberId, action, from, to);
    }

    @GetMapping("/export")
    public void export(
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID memberId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            HttpServletResponse response) throws IOException {
        byte[] bytes = service.export(customerId, memberId, action, from, to);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=yingge-audit.xlsx");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    public record AuditResponse(String action, String entityType, String entityId, String outcome,
                                Map<String, Object> details, String traceId, UUID actorMembershipId,
                                String actorDisplayName, Instant createdAt) {}
}
