package com.yingge.cloud.assistant.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.assistant.api.AssistantDtos;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class QqConversationDraftParser {
    private static final int MAX_RESPONSE_CHARS = 200_000;
    private static final Set<String> EXPECTED_FIELDS = Set.of(
            "summary", "customerQuotes", "goalsMotivation", "actionsTaken", "mainConcerns",
            "decisionParticipants", "productFit", "suggestedFocus", "explicitTaboos", "outcome",
            "nextStep", "nextFollowUpAt", "uncertainties");
    private static final List<String> WRAPPER_FIELDS = List.of("result", "draft", "data", "output", "response", "content");

    private final ObjectMapper objectMapper;

    QqConversationDraftParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    AssistantDtos.QqConversationDraft parse(String content) {
        JsonNode root = requireDraftObject(content);
        List<String> uncertainties = new ArrayList<>(stringList(root.get("uncertainties"), 50, 2_000));
        String rawNextFollowUpAt = text(root.get("nextFollowUpAt"), 200, null);
        Instant nextFollowUpAt = parseInstant(rawNextFollowUpAt);
        if (rawNextFollowUpAt != null && nextFollowUpAt == null && !isNullLike(rawNextFollowUpAt)) {
            addUnique(uncertainties, "模型返回的建议跟进时间无法识别，已留空，请人工确认", 50);
        }
        if (EXPECTED_FIELDS.stream().anyMatch(field -> !root.has(field))
                || !root.path("customerQuotes").isArray() || !root.path("uncertainties").isArray()) {
            addUnique(uncertainties, "模型未返回完整结构，请人工核实未确认字段", 50);
        }

        return new AssistantDtos.QqConversationDraft(
                text(root.get("summary"), 10_000, "未确认"),
                stringList(root.get("customerQuotes"), 50, 2_000),
                text(root.get("goalsMotivation"), 10_000, "未确认"),
                text(root.get("actionsTaken"), 10_000, "未确认"),
                text(root.get("mainConcerns"), 10_000, "未确认"),
                text(root.get("decisionParticipants"), 6_000, "未确认"),
                text(root.get("productFit"), 10_000, "未确认"),
                text(root.get("suggestedFocus"), 10_000, "未确认"),
                text(root.get("explicitTaboos"), 10_000, "未确认"),
                text(root.get("outcome"), 10_000, "未确认"),
                text(root.get("nextStep"), 10_000, "未确认"),
                nextFollowUpAt,
                List.copyOf(uncertainties));
    }

    private JsonNode requireDraftObject(String content) {
        if (content == null || content.isBlank() || content.length() > MAX_RESPONSE_CHARS) {
            throw new IllegalArgumentException("AI response is empty or too large");
        }
        String sanitized = content.trim().replaceAll("(?is)<think\\b[^>]*>.*?</think>", "").trim();
        List<String> candidates = new ArrayList<>();
        candidates.add(stripFences(sanitized));
        candidates.addAll(jsonObjects(sanitized));
        for (String candidate : new LinkedHashSet<>(candidates)) {
            JsonNode parsed = parseNode(candidate);
            JsonNode unwrapped = unwrap(parsed);
            if (unwrapped != null && unwrapped.isObject()
                    && EXPECTED_FIELDS.stream().anyMatch(unwrapped::has)) {
                return unwrapped;
            }
        }
        throw new IllegalArgumentException("AI response does not contain a QQ draft object");
    }

    private JsonNode parseNode(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JsonNode unwrap(JsonNode value) {
        JsonNode current = value;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (current.isTextual()) {
                current = parseNode(stripFences(current.asText()));
                continue;
            }
            if (!current.isObject() || EXPECTED_FIELDS.stream().anyMatch(current::has)) return current;
            JsonNode nested = null;
            for (String field : WRAPPER_FIELDS) {
                JsonNode candidate = current.get(field);
                if (candidate != null && (candidate.isObject() || candidate.isTextual())) {
                    nested = candidate;
                    break;
                }
            }
            if (nested == null) return current;
            current = nested;
        }
        return current;
    }

    private List<String> jsonObjects(String value) {
        List<String> objects = new ArrayList<>();
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (inString) {
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                if (depth == 0) start = index;
                depth++;
            } else if (character == '}' && depth > 0) {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(value.substring(start, index + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private List<String> stringList(JsonNode value, int maxItems, int maxChars) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isNull()) return result;
        if (value.isArray()) {
            value.forEach(item -> addTextItem(result, item, maxItems, maxChars));
        } else {
            addTextItem(result, value, maxItems, maxChars);
        }
        return List.copyOf(result);
    }

    private void addTextItem(List<String> result, JsonNode item, int maxItems, int maxChars) {
        if (result.size() >= maxItems || item == null || item.isNull()) return;
        JsonNode source = item;
        if (item.isObject()) {
            for (String field : List.of("quote", "text", "content", "value")) {
                if (item.has(field)) {
                    source = item.get(field);
                    break;
                }
            }
        }
        String normalized = text(source, maxChars, null);
        if (normalized != null) result.add(normalized);
    }

    private String text(JsonNode value, int maxChars, String fallback) {
        if (value == null || value.isNull() || value.isContainerNode()) return fallback;
        String normalized = value.asText().trim();
        if (normalized.isBlank() || isNullLike(normalized)) return fallback;
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private Instant parseInstant(String value) {
        if (value == null || isNullLike(value)) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private boolean isNullLike(String value) {
        return value == null || value.isBlank() || "null".equalsIgnoreCase(value)
                || "未确认".equals(value) || "无".equals(value) || "暂无".equals(value);
    }

    private void addUnique(List<String> values, String value, int maxItems) {
        if (values.size() < maxItems && !values.contains(value)) values.add(value);
    }

    private String stripFences(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("```")) {
            return trimmed.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        return trimmed;
    }
}
