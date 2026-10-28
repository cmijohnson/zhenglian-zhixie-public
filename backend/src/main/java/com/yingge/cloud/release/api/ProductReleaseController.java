package com.yingge.cloud.release.api;

import com.yingge.cloud.release.application.ProductReleaseService;
import com.yingge.cloud.shared.api.PageResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/releases")
public class ProductReleaseController {
    private final ProductReleaseService releases;

    public ProductReleaseController(ProductReleaseService releases) {
        this.releases = releases;
    }

    @GetMapping
    public PageResponse<ReleaseDtos.Response> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return releases.list(page, size);
    }

    @GetMapping("/current")
    public ReleaseDtos.Response current() {
        return releases.current();
    }

    @GetMapping("/current/unacknowledged")
    public ResponseEntity<ReleaseDtos.Response> unacknowledgedCurrent() {
        ReleaseDtos.Response release = releases.unacknowledgedCurrent();
        return release == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(release);
    }

    @PostMapping("/{releaseId}/acknowledge")
    public ResponseEntity<Void> acknowledge(@PathVariable UUID releaseId) {
        releases.acknowledge(releaseId);
        return ResponseEntity.noContent().build();
    }
}
