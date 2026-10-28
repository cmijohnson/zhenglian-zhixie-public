package com.yingge.cloud.tag.application;

import com.yingge.cloud.audit.application.AuditService;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.domain.CustomerQuality;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.tag.api.TagController;
import com.yingge.cloud.tag.infrastructure.DictionaryItemEntity;
import com.yingge.cloud.tag.infrastructure.DictionaryItemRepository;
import com.yingge.cloud.tag.infrastructure.TagEntity;
import com.yingge.cloud.tag.infrastructure.TagRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class TagApplicationService {
    private final TagRepository tags;
    private final DictionaryItemRepository dictionaries;
    private final CurrentActorProvider actors;
    private final PermissionService permissions;
    private final AuditService audit;

    public TagApplicationService(TagRepository tags, DictionaryItemRepository dictionaries,
                                 CurrentActorProvider actors, PermissionService permissions, AuditService audit) {
        this.tags = tags;
        this.dictionaries = dictionaries;
        this.actors = actors;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<TagController.TagResponse> listTags() {
        var actor = actors.require();
        return tags.findByOrganizationIdAndActiveTrueOrderByCategoryAscNameAsc(actor.organizationId()).stream()
                .map(this::tagResponse).toList();
    }

    @Transactional
    public TagController.TagResponse createTag(TagController.CreateTagRequest body) {
        var actor = actors.require();
        permissions.requireCustomerWrite(actor);
        if (isGradeTag(body.name(), body.category())) {
            throw ApiException.badRequest("GRADE_TAG_NOT_ALLOWED", "年级请使用客户画像中的年级选项，不要重复创建标签");
        }
        if (CustomerQuality.isReserved(body.name(), body.category())) {
            throw ApiException.badRequest("CUSTOMER_QUALITY_TAG_RESERVED",
                    "客户质量为系统分层，请在客户档案中选择 A、B 或 C 类");
        }
        try {
            TagEntity saved = tags.saveAndFlush(new TagEntity(actor.organizationId(), body.name().trim(),
                    body.category().trim(), body.color(), actor.membershipId()));
            audit.record(actor, "TAG_CREATED", "TAG", saved.getId().toString(), "SUCCESS", null);
            return tagResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("TAG_NAME_EXISTS", "同名标签已存在");
        }
    }

    @Transactional(readOnly = true)
    public List<TagController.DictionaryResponse> dictionaries() {
        var actor = actors.require();
        return dictionaries.findByOrganizationIdAndActiveTrueOrderByTypeAscSortOrderAsc(actor.organizationId()).stream()
                .map(this::dictionaryResponse).toList();
    }

    @Transactional
    public TagController.DictionaryResponse createDictionary(TagController.CreateDictionaryRequest body) {
        var actor = actors.require();
        permissions.requireAdmin(actor);
        try {
            DictionaryItemEntity saved = dictionaries.saveAndFlush(new DictionaryItemEntity(actor.organizationId(),
                    body.type(), body.code(), body.label().trim(), body.sortOrder()));
            audit.record(actor, "DICTIONARY_CREATED", "DICTIONARY", saved.getId().toString(), "SUCCESS", null);
            return dictionaryResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict("DICTIONARY_CODE_EXISTS", "同类字典编码已存在");
        }
    }

    @Transactional
    public TagController.DictionaryResponse createSchool(TagController.CreateSchoolRequest body) {
        return createSimpleDictionary("SCHOOL", "school-", body.label(), "SCHOOL_DICTIONARY_CREATED",
                "SCHOOL_EXISTS", "该学校已在选项中", false);
    }

    @Transactional
    public TagController.DictionaryResponse createSource(TagController.CreateSourceRequest body) {
        return createSimpleDictionary("SOURCE_CHANNEL", "source-", body.label(), "SOURCE_DICTIONARY_CREATED",
                "SOURCE_EXISTS", "该来源已在选项中", true);
    }

    private TagController.DictionaryResponse createSimpleDictionary(
            String type, String prefix, String rawLabel, String action, String errorCode, String errorMessage,
            boolean precheckLabel
    ) {
        var actor = actors.require();
        permissions.requireCustomerWrite(actor);
        String label = rawLabel.trim();
        if (precheckLabel && dictionaries.existsByOrganizationIdAndTypeAndActiveTrueAndLabelIgnoreCase(
                actor.organizationId(), type, label)) {
            throw ApiException.conflict(errorCode, errorMessage);
        }
        int sortOrder = Math.toIntExact(dictionaries.countByOrganizationIdAndTypeAndActiveTrue(
                actor.organizationId(), type) + 1);
        try {
            DictionaryItemEntity saved = dictionaries.saveAndFlush(new DictionaryItemEntity(
                    actor.organizationId(), type, prefix + UUID.randomUUID(), label, sortOrder));
            audit.record(actor, action, "DICTIONARY", saved.getId().toString(), "SUCCESS", null);
            return dictionaryResponse(saved);
        } catch (DataIntegrityViolationException exception) {
            throw ApiException.conflict(errorCode, errorMessage);
        }
    }

    private boolean isGradeTag(String name, String category) {
        String normalizedName = name.trim();
        return "年级".equals(category.trim()) || normalizedName.matches("^(?:(?:19|20)\\d{2}|\\d{2})(?:级)?$");
    }

    private TagController.TagResponse tagResponse(TagEntity tag) {
        return new TagController.TagResponse(tag.getId().toString(), tag.getName(), tag.getCategory(),
                tag.getColor(), tag.isSystem());
    }

    private TagController.DictionaryResponse dictionaryResponse(DictionaryItemEntity item) {
        return new TagController.DictionaryResponse(item.getId().toString(), item.getType(), item.getCode(),
                item.getLabel(), item.getSortOrder());
    }
}
