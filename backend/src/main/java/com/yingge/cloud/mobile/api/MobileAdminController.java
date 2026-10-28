package com.yingge.cloud.mobile.api;

import com.yingge.cloud.mobile.application.MobileAdminOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/mobile/admin")
public class MobileAdminController {
    private final MobileAdminOverviewService overview;

    public MobileAdminController(MobileAdminOverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/overview")
    public MobileDtos.AdminOverview overview() {
        return overview.overview();
    }
}
