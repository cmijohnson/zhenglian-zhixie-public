package com.yingge.cloud.content.api;

import com.yingge.cloud.content.application.ContentService;
import com.yingge.cloud.content.domain.ContentPlatform;
import com.yingge.cloud.content.domain.ContentReviewStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import com.yingge.cloud.shared.api.PageResponse;

@RestController
@RequestMapping("/api/v1")
public class ContentController {
    private final ContentService content;

    public ContentController(ContentService content) {
        this.content = content;
    }

    @GetMapping("/content-tasks/overview")
    public ContentDtos.OverviewResponse overview() {
        return content.overview();
    }

    @GetMapping("/circle/posts")
    public PageResponse<ContentDtos.PostResponse> circle(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID membershipId,
            @RequestParam(required = false) ContentPlatform platform,
            @RequestParam(required = false) ContentReviewStatus reviewStatus,
            @RequestParam(defaultValue = "recommended") String sort) {
        return content.circle(page, size, search, membershipId, platform, reviewStatus, sort);
    }

    @GetMapping("/circle/senders")
    public java.util.List<ContentDtos.SenderOption> circleSenders() {
        return content.circleSenders();
    }

    @PostMapping("/content-directions")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentDtos.DirectionResponse createDirection(@Valid @RequestBody ContentDtos.DirectionRequest body) {
        return content.createDirection(body);
    }

    @PatchMapping("/content-directions/{id}")
    public ContentDtos.DirectionResponse updateDirection(@PathVariable UUID id,
                                                         @Valid @RequestBody ContentDtos.DirectionRequest body) {
        return content.updateDirection(id, body);
    }

    @PostMapping("/content-posts")
    @ResponseStatus(HttpStatus.CREATED)
    public ContentDtos.PostResponse submit(@Valid @RequestBody ContentDtos.PostRequest body) {
        return content.submit(body);
    }

    @PatchMapping("/content-posts/{id}/metrics")
    public ContentDtos.PostResponse metrics(@PathVariable UUID id,
                                            @Valid @RequestBody ContentDtos.MetricsRequest body) {
        return content.updateMetrics(id, body);
    }

    @PatchMapping("/content-posts/{id}/review")
    public ContentDtos.PostResponse review(@PathVariable UUID id,
                                           @Valid @RequestBody ContentDtos.ReviewRequest body) {
        return content.review(id, body);
    }
}
