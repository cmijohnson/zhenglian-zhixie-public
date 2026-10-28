package com.yingge.cloud.reminder.api;

import com.yingge.cloud.followup.application.CustomerFollowUpService;
import com.yingge.cloud.reminder.application.ReminderFacade;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {
    private final ReminderFacade service;
    private final CustomerFollowUpService followUps;

    public ReminderController(ReminderFacade service, CustomerFollowUpService followUps) {
        this.service = service;
        this.followUps = followUps;
    }

    @GetMapping
    public List<ReminderDtos.Response> list() { return service.list(); }

    @GetMapping("/urgent-a-class")
    public List<ReminderDtos.Response> urgentAClass() {
        followUps.createStaleRemindersForCurrentActor();
        return service.urgentAClassStale();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReminderDtos.Response create(@Valid @RequestBody ReminderDtos.CreateRequest request) {
        return service.create(request);
    }

    @PatchMapping("/{id}")
    public ReminderDtos.Response update(@PathVariable UUID id, @Valid @RequestBody ReminderDtos.UpdateRequest request) {
        return service.update(id, request);
    }
}
