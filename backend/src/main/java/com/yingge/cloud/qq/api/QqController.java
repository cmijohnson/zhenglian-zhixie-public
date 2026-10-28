package com.yingge.cloud.qq.api;

import com.yingge.cloud.qq.application.QqIntegrationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class QqController {
    private final QqIntegrationService service;

    public QqController(QqIntegrationService service) { this.service = service; }

    @GetMapping("/qq-connections/me")
    public ResponseEntity<QqDtos.Connection> mine() {
        QqDtos.Connection connection = service.myConnection();
        return connection == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(connection);
    }

    @PostMapping("/qq-connections")
    public QqDtos.Connection create() { return service.createConnection(); }

    @DeleteMapping("/qq-connections/me")
    public void revokeMine() { service.revokeMine(); }

    @GetMapping("/qq-connections/friends")
    public QqDtos.FriendPage friends(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "100") int limit) {
        return service.friends(page, limit);
    }

    @PostMapping("/qq-imports/preview")
    public QqDtos.Preview preview(@Valid @RequestBody QqDtos.PreviewRequest request) {
        return service.preview(request);
    }

    @PostMapping("/qq-imports/{id}/confirm")
    public QqDtos.ConfirmResult confirm(@PathVariable UUID id,
                                        @Valid @RequestBody QqDtos.ConfirmRequest request) {
        return service.confirm(id, request);
    }

    @GetMapping("/qq-imports/{id}/raw")
    public QqDtos.Preview raw(@PathVariable UUID id) { return service.raw(id); }

    @GetMapping("/admin/qq-connections")
    public List<QqDtos.AdminConnection> adminConnections() { return service.adminConnections(); }

    @DeleteMapping("/admin/qq-connections/{id}")
    public void revoke(@PathVariable UUID id) { service.revokeConnection(id); }
}
