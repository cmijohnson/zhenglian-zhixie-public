package com.yingge.cloud.tag.api;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.tag.infrastructure.DictionaryItemEntity;
import com.yingge.cloud.tag.infrastructure.DictionaryItemRepository;
import com.yingge.cloud.tag.infrastructure.TagEntity;
import com.yingge.cloud.tag.infrastructure.TagRepository;
import com.yingge.cloud.tag.application.TagApplicationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TagController {
    private final TagApplicationService service;

    public TagController(TagApplicationService service) {
        this.service = service;
    }

    @GetMapping("/tags")
    public List<TagResponse> listTags() {
        return service.listTags();
    }

    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public TagResponse createTag(@Valid @RequestBody CreateTagRequest body) {
        return service.createTag(body);
    }

    private static boolean isGradeTag(String name, String category) {
        String normalizedName = name.trim();
        return "年级".equals(category.trim())
                || normalizedName.matches("^(?:(?:19|20)\\d{2}|\\d{2})(?:级)?$");
    }

    @GetMapping("/dictionaries")
    public List<DictionaryResponse> dictionaries() {
        return service.dictionaries();
    }

    @PostMapping("/dictionaries")
    @ResponseStatus(HttpStatus.CREATED)
    public DictionaryResponse createDictionary(@Valid @RequestBody CreateDictionaryRequest body) {
        return service.createDictionary(body);
    }

    @PostMapping("/dictionaries/schools")
    @ResponseStatus(HttpStatus.CREATED)
    public DictionaryResponse createSchool(@Valid @RequestBody CreateSchoolRequest body) {
        return service.createSchool(body);
    }

    @PostMapping("/dictionaries/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public DictionaryResponse createSource(@Valid @RequestBody CreateSourceRequest body) {
        return service.createSource(body);
    }

    public record CreateTagRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Size(max = 60) String category,
            @NotBlank @Pattern(regexp = "#[0-9a-fA-F]{6}") String color
    ) {}

    public record TagResponse(String id, String name, String category, String color, boolean system) {
        static TagResponse from(TagEntity tag) {
            return new TagResponse(tag.getId().toString(), tag.getName(), tag.getCategory(), tag.getColor(), tag.isSystem());
        }
    }

    public record CreateDictionaryRequest(
            @NotBlank @Pattern(regexp = "SOURCE_CHANNEL|FOCUS_DIRECTION|INTERACTION_CHANNEL|SCHOOL") String type,
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 120) String label,
            int sortOrder
    ) {}

    public record CreateSchoolRequest(@NotBlank @Size(max = 120) String label) {}

    public record CreateSourceRequest(@NotBlank @Size(max = 120) String label) {}

    public record DictionaryResponse(String id, String type, String code, String label, int sortOrder) {
        static DictionaryResponse from(DictionaryItemEntity item) {
            return new DictionaryResponse(item.getId().toString(), item.getType(), item.getCode(), item.getLabel(), item.getSortOrder());
        }
    }
}
