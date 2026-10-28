package com.yingge.cloud.business.api;

import com.yingge.cloud.business.application.CrossSaleQueryService;
import com.yingge.cloud.business.domain.BusinessCycleStatus;
import com.yingge.cloud.shared.api.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/cross-sales")
public class AdminCrossSaleController {
    private final CrossSaleQueryService service;

    public AdminCrossSaleController(CrossSaleQueryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<CrossSaleDtos.Response> search(
            @RequestParam(required = false) Boolean crossSale,
            @RequestParam(required = false) UUID ownerMembershipId,
            @RequestParam(required = false) UUID collaboratorMembershipId,
            @RequestParam(required = false) BusinessCycleStatus status,
            @RequestParam(required = false) Instant markedFrom,
            @RequestParam(required = false) Instant markedTo,
            @RequestParam(required = false) String quality,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.search(crossSale, ownerMembershipId, collaboratorMembershipId, status,
                markedFrom, markedTo, quality, page, size);
    }

    @GetMapping("/export.xlsx")
    public void export(
            @RequestParam(required = false) Boolean crossSale,
            @RequestParam(required = false) UUID ownerMembershipId,
            @RequestParam(required = false) UUID collaboratorMembershipId,
            @RequestParam(required = false) BusinessCycleStatus status,
            @RequestParam(required = false) Instant markedFrom,
            @RequestParam(required = false) Instant markedTo,
            @RequestParam(required = false) String quality,
            HttpServletResponse response) throws java.io.IOException {
        byte[] bytes = service.export(crossSale, ownerMembershipId, collaboratorMembershipId,
                status, markedFrom, markedTo, quality);
        String filename = URLEncoder.encode("交叉单查询-" + LocalDate.now() + ".xlsx", StandardCharsets.UTF_8)
                .replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + filename);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }
}
