package com.yingge.cloud.task.api;

import com.yingge.cloud.task.application.TaskService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/v1/tasks")
public class TaskController {
    private final TaskService tasks;

    public TaskController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public List<TaskDtos.TaskResponse> list() {
        return tasks.list();
    }

    @GetMapping("/matrix")
    public List<TaskDtos.MatrixRow> matrix() {
        return tasks.matrix();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDtos.TaskResponse create(@Valid @RequestBody TaskDtos.CreateRequest body) {
        return tasks.create(body);
    }

    @PostMapping("/{taskId}/claim")
    public TaskDtos.AssignmentResponse claim(@PathVariable UUID taskId) {
        return tasks.claim(taskId);
    }

    @PatchMapping("/{taskId}/my-assignment")
    public TaskDtos.AssignmentResponse updateMine(@PathVariable UUID taskId,
                                                  @Valid @RequestBody TaskDtos.MemberUpdateRequest body) {
        return tasks.updateMine(taskId, body);
    }

    @GetMapping("/{taskId}/assignments")
    public List<TaskDtos.AssignmentResponse> assignments(@PathVariable UUID taskId) {
        return tasks.assignmentList(taskId);
    }

    @PatchMapping("/{taskId}/assignments/{assignmentId}")
    public TaskDtos.AssignmentResponse review(@PathVariable UUID taskId, @PathVariable UUID assignmentId,
                                              @Valid @RequestBody TaskDtos.ReviewRequest body) {
        return tasks.review(taskId, assignmentId, body);
    }

    @PostMapping("/{taskId}/close")
    public ResponseEntity<Void> close(@PathVariable UUID taskId) {
        tasks.close(taskId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{taskId}")
    public ResponseEntity<Void> delete(@PathVariable UUID taskId) {
        tasks.delete(taskId);
        return ResponseEntity.noContent().build();
    }
}
