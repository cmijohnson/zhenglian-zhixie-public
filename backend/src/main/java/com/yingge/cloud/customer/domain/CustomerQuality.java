package com.yingge.cloud.customer.domain;

import java.util.List;

public final class CustomerQuality {
    public static final String CATEGORY = "客户质量";
    public static final List<Level> LEVELS = List.of(
            new Level("A", "A类用户", "存在明确真实需求，并有近期推进信号和可追溯证据", "#16A34A"),
            new Level("B", "B类用户", "需求真实，但时间、决策或推进条件尚未成熟", "#F59E0B"),
            new Level("C", "C类用户", "当前无需求、明确拒绝或需要长期沉淀", "#64748B")
    );

    private CustomerQuality() {
    }

    public static boolean isReserved(String name, String category) {
        String normalizedName = name == null ? "" : name.trim();
        String normalizedCategory = category == null ? "" : category.trim();
        return CATEGORY.equals(normalizedCategory)
                || LEVELS.stream().anyMatch(level -> level.label().equalsIgnoreCase(normalizedName));
    }

    public static boolean hasMultipleQualityTags(List<String> categories) {
        return categories.stream().filter(CATEGORY::equals).limit(2).count() > 1;
    }

    public record Level(String code, String label, String description, String color) {
    }
}
