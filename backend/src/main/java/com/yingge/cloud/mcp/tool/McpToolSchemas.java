package com.yingge.cloud.mcp.tool;

import io.modelcontextprotocol.spec.McpSchema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Generates the strict JSON schemas advertised by the MCP tools. */
public final class McpToolSchemas {
    private McpToolSchemas() {}

    public static McpSchema.JsonSchema empty() {
        return object(Map.of(), List.of());
    }

    public static McpSchema.JsonSchema forRecord(Class<?> type, Map<String, Map<String, Object>> additions) {
        if (!type.isRecord()) throw new IllegalArgumentException("MCP input type must be a record: " + type.getName());
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent component : type.getRecordComponents()) {
            Map<String, Object> property = schema(component.getAnnotatedType());
            Size size = component.getAnnotation(Size.class);
            if (size != null) {
                property.put("minLength", size.min());
                property.put("maxLength", size.max());
            }
            Pattern pattern = component.getAnnotation(Pattern.class);
            if (pattern != null) property.put("pattern", pattern.regexp());
            if (component.isAnnotationPresent(Positive.class)) property.put("exclusiveMinimum", 0);
            if (component.isAnnotationPresent(PositiveOrZero.class)) property.put("minimum", 0);
            properties.put(component.getName(), property);
            if (component.isAnnotationPresent(NotNull.class) || component.isAnnotationPresent(NotBlank.class)
                    || component.getType().isPrimitive()) {
                required.add(component.getName());
            }
        }
        additions.forEach(properties::put);
        return object(properties, required);
    }

    public static McpSchema.JsonSchema fields(Map<String, Map<String, Object>> fields, String... required) {
        return object(new LinkedHashMap<>(fields), List.of(required));
    }

    public static Map<String, Object> string() { return new LinkedHashMap<>(Map.of("type", "string")); }
    public static Map<String, Object> uuid() { return new LinkedHashMap<>(Map.of("type", "string", "format", "uuid")); }
    public static Map<String, Object> integer() { return new LinkedHashMap<>(Map.of("type", "integer")); }
    public static Map<String, Object> bool() { return new LinkedHashMap<>(Map.of("type", "boolean")); }
    public static Map<String, Object> idempotencyKey() {
        return new LinkedHashMap<>(Map.of("type", "string", "maxLength", 160,
                "description", "Optional client-generated retry key; retained for 24 hours."));
    }
    public static Map<String, Object> approvalId() {
        return new LinkedHashMap<>(Map.of("type", "string", "format", "uuid",
                "description", "One-time approval identifier returned by the first call."));
    }

    private static McpSchema.JsonSchema object(Map<String, Object> properties, List<String> required) {
        return new McpSchema.JsonSchema("object", properties, required, false, null, null);
    }

    private static Map<String, Object> schema(AnnotatedType annotatedType) {
        Class<?> raw = rawClass(annotatedType.getType());
        if (raw == String.class || raw == Character.class || raw == char.class) return string();
        if (raw == UUID.class) return uuid();
        if (raw == Instant.class) return new LinkedHashMap<>(Map.of("type", "string", "format", "date-time"));
        if (raw == LocalDate.class) return new LinkedHashMap<>(Map.of("type", "string", "format", "date"));
        if (raw == Boolean.class || raw == boolean.class) return bool();
        if (Number.class.isAssignableFrom(raw) || raw.isPrimitive()) return integer();
        if (raw.isEnum()) {
            List<String> values = java.util.Arrays.stream(raw.getEnumConstants()).map(Object::toString).toList();
            return new LinkedHashMap<>(Map.of("type", "string", "enum", values));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            AnnotatedType itemType = Object.class.getAnnotatedSuperclass();
            if (annotatedType instanceof AnnotatedParameterizedType parameterized) {
                itemType = parameterized.getAnnotatedActualTypeArguments()[0];
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", "array");
            result.put("items", schema(itemType));
            if (java.util.Set.class.isAssignableFrom(raw)) result.put("uniqueItems", true);
            return result;
        }
        if (raw.isRecord()) {
            McpSchema.JsonSchema nested = forRecord(raw, Map.of());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", nested.type());
            result.put("properties", nested.properties());
            result.put("required", nested.required());
            result.put("additionalProperties", false);
            return result;
        }
        return new LinkedHashMap<>(Map.of("type", "object"));
    }

    private static Class<?> rawClass(java.lang.reflect.Type type) {
        if (type instanceof Class<?> clazz) return clazz;
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return Object.class;
    }
}
