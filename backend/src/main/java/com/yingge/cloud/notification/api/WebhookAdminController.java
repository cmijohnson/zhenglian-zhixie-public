package com.yingge.cloud.notification.api;

import com.yingge.cloud.notification.application.NotificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/admin/wecom-webhooks")
public class WebhookAdminController {
    private final NotificationService service;

    public WebhookAdminController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    public List<NotificationDtos.WebhookResponse> list() {
        return service.webhooks();
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public NotificationDtos.WebhookResponse create(@Valid @RequestBody NotificationDtos.WebhookRequest body) {
        return service.createWebhook(body);
    }

    @PutMapping("/{id}")
    public NotificationDtos.WebhookResponse update(@PathVariable UUID id,
                                                    @Valid @RequestBody NotificationDtos.WebhookRequest body) {
        return service.updateWebhook(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        service.disableWebhook(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/deliveries")
    public List<NotificationDtos.DeliveryResponse> deliveries(@RequestParam(defaultValue = "100") int size) {
        return service.deliveries(size);
    }

    @PostMapping("/deliveries/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable UUID id) {
        service.retryDelivery(id);
        return ResponseEntity.noContent().build();
    }
}
