package com.yingge.cloud.mobile.api;

import com.yingge.cloud.mobile.application.MobileAssistantService;
import com.yingge.cloud.shared.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mobile")
public class MobileAssistantController {
    private final MobileAssistantService service;

    public MobileAssistantController(MobileAssistantService service) {
        this.service = service;
    }

    @PostMapping("/customers/{customerId}/insight")
    public MobileDtos.CustomerInsight insight(@PathVariable UUID customerId, HttpServletRequest request) {
        return service.insight(customerId, traceId(request));
    }

    @PostMapping("/dashboard/greeting")
    public MobileDtos.DashboardGreeting greeting(HttpServletRequest request) {
        return service.greeting(traceId(request));
    }

    private String traceId(HttpServletRequest request) {
        Object value = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        return value == null ? "" : value.toString();
    }
}
