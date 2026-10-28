package com.yingge.cloud.mobile.api;

import com.yingge.cloud.mobile.application.MobileReleaseService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mobile/app-releases")
public class MobileReleaseController {
    private final MobileReleaseService service;

    public MobileReleaseController(MobileReleaseService service) {
        this.service = service;
    }

    @GetMapping("/current")
    public ResponseEntity<MobileDtos.AppRelease> current(
            @RequestHeader(value = "X-Yingge-Version-Code", defaultValue = "0") long versionCode,
            @RequestHeader(value = "X-Yingge-Platform", defaultValue = "ANDROID") MobileDtos.Platform platform) {
        MobileDtos.AppRelease release = service.current(platform, versionCode);
        return release == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(release);
    }

    @GetMapping(value = "/{releaseId}/apk", produces = "application/vnd.android.package-archive")
    public ResponseEntity<org.springframework.core.io.Resource> apk(@PathVariable UUID releaseId) {
        MobileReleaseService.Apk apk = service.apk(releaseId);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.android.package-archive"))
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=yingge-cloud.apk")
                .header("X-Content-SHA256", apk.sha256() == null ? "" : apk.sha256());
        if (apk.size() != null) response.contentLength(apk.size());
        return response.body(apk.resource());
    }
}
