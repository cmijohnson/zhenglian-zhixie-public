package com.yingge.cloud.announcement.api;

import com.yingge.cloud.announcement.application.AnnouncementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/announcements")
public class AnnouncementController {
    private final AnnouncementService announcements;

    public AnnouncementController(AnnouncementService announcements) {
        this.announcements = announcements;
    }

    @GetMapping
    public List<AnnouncementDtos.Response> list(
            @RequestParam(defaultValue = "false") boolean includeArchived) {
        return announcements.list(includeArchived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementDtos.Response create(@Valid @RequestBody AnnouncementDtos.UpsertRequest body) {
        return announcements.create(body);
    }

    @PatchMapping("/{id}")
    public AnnouncementDtos.Response update(@PathVariable UUID id,
                                             @Valid @RequestBody AnnouncementDtos.UpsertRequest body) {
        return announcements.update(id, body);
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(@PathVariable UUID id) {
        announcements.archive(id);
        return ResponseEntity.noContent().build();
    }
}
