package com.yingge.cloud.feishu.api;

import com.yingge.cloud.feishu.application.FeishuAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/feishu-bindings")
public class FeishuBindingAdminController {
    private final FeishuAuthService service;

    public FeishuBindingAdminController(FeishuAuthService service) {
        this.service = service;
    }

    @GetMapping
    public List<FeishuAuthDtos.AdminBindingResponse> list() {
        return service.adminBindings();
    }

    @DeleteMapping("/{bindingId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID bindingId) {
        service.adminRevoke(bindingId);
        return ResponseEntity.noContent().build();
    }
}
