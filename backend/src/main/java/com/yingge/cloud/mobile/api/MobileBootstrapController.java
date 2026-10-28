package com.yingge.cloud.mobile.api;

import com.yingge.cloud.mobile.application.MobileBootstrapService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mobile/bootstrap")
public class MobileBootstrapController {
    private final MobileBootstrapService service;

    public MobileBootstrapController(MobileBootstrapService service) {
        this.service = service;
    }

    @GetMapping
    public MobileDtos.Bootstrap bootstrap() {
        return service.bootstrap();
    }
}
