package com.yingge.cloud.forum.api;

import com.yingge.cloud.forum.application.ForumService;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/forum")
public class ForumController {
    private static final Set<String> INLINE_ATTACHMENT_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE,
            MediaType.IMAGE_GIF_VALUE,
            "image/webp",
            "image/avif",
            "image/bmp",
            MediaType.APPLICATION_PDF_VALUE
    );

    private final ForumService forum;

    public ForumController(ForumService forum) {
        this.forum = forum;
    }

    @GetMapping("/public/boards")
    public List<ForumDtos.Board> boards() {
        return forum.listBoards(false);
    }

    @GetMapping("/public/posts")
    public ForumDtos.Page<ForumDtos.PostSummary> posts(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String board,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return forum.listPosts(query, board, tag, type, page, size);
    }

    @GetMapping("/public/posts/{id}")
    public ForumDtos.PostDetail post(@PathVariable UUID id) {
        return forum.getPost(id, true);
    }

    @GetMapping("/public/attachments/{id}")
    public ResponseEntity<ByteArrayResource> attachment(@PathVariable UUID id) {
        var file = forum.getAttachment(id);
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(file.contentType());
        } catch (IllegalArgumentException ignored) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }
        var dispositionType = INLINE_ATTACHMENT_TYPES.contains(contentType.toString().toLowerCase())
                ? "inline"
                : "attachment";
        var disposition = dispositionType + "; filename*=UTF-8''" +
                java.net.URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(contentType)
                .contentLength(file.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header("X-Content-Type-Options", "nosniff")
                .body(new ByteArrayResource(file.content()));
    }

    @PostMapping("/posts")
    @ResponseStatus(HttpStatus.CREATED)
    public ForumDtos.PostDetail create(@Valid @RequestBody ForumDtos.CreatePost body) {
        return forum.createPost(body);
    }

    @PutMapping("/posts/{id}")
    public ForumDtos.PostDetail update(@PathVariable UUID id, @Valid @RequestBody ForumDtos.UpdatePost body) {
        return forum.updatePost(id, body);
    }

    @PostMapping(value = "/posts/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ForumDtos.Attachment upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return forum.addAttachment(id, file);
    }

    @PostMapping("/posts/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public ForumDtos.Comment comment(@PathVariable UUID id,
                                     @Valid @RequestBody ForumDtos.CreateComment body) {
        return forum.addComment(id, body);
    }

    @PostMapping("/posts/{id}/like")
    public ForumDtos.LikeResult like(@PathVariable UUID id) {
        return forum.toggleLike(id);
    }

    @PostMapping("/posts/{id}/report")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<Void> report(@PathVariable UUID id,
                                       @Valid @RequestBody ForumDtos.ReportRequest body) {
        forum.report(id, body);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/announcements/{id}/read")
    public ResponseEntity<Void> readAnnouncement(@PathVariable UUID id) {
        forum.markAnnouncementRead(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/manage/boards")
    public List<ForumDtos.Board> manageBoards() {
        return forum.listBoards(true);
    }

    @PostMapping("/manage/boards")
    @ResponseStatus(HttpStatus.CREATED)
    public ForumDtos.Board createBoard(@Valid @RequestBody ForumDtos.BoardRequest body) {
        return forum.createBoard(body);
    }

    @PatchMapping("/manage/boards/{id}")
    public ForumDtos.Board updateBoard(@PathVariable UUID id,
                                       @Valid @RequestBody ForumDtos.BoardRequest body) {
        return forum.updateBoard(id, body);
    }

    @GetMapping("/manage/reports")
    public List<ForumDtos.Report> reports() {
        return forum.listReports();
    }

    @PostMapping("/manage/posts/{id}/actions")
    public ForumDtos.PostDetail moderate(@PathVariable UUID id,
                                         @Valid @RequestBody ForumDtos.ModerationRequest body) {
        return forum.moderate(id, body);
    }

    @GetMapping("/manage/overview")
    public ForumDtos.Overview overview() {
        return forum.overview();
    }
}
