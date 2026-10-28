package com.yingge.cloud.customer.domain;

import com.yingge.cloud.shared.api.ApiException;
import com.yingge.cloud.shared.domain.TextNormalizer;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

public final class CustomerOptions {
    public static final Set<String> IDENTITY_TYPES = Set.of("新生", "转专业", "在校生", "毕业生", "家长代问", "其他");
    public static final Set<String> LEVELS = Set.of("低", "中", "高");
    public static final Set<String> VERIFICATION_STATUSES = Set.of("未核实", "已核实", "部分待确认");
    public static final Set<String> STATUSES = Set.of("初次接触", "待核实", "已沟通", "跟进中", "高意向", "待决定", "已转化", "暂缓", "无效", "归档");
    private static final Pattern FULL_GRADE = Pattern.compile("^(20\\d{2})(?:级|届)?$");
    private static final Pattern SHORT_GRADE = Pattern.compile("^([23]\\d)(?:级|届)?$");

    private CustomerOptions() {
    }

    public static void validate(String identityType, String intention, String urgency, String verification, String status) {
        require(IDENTITY_TYPES, identityType, "identityType", "身份类型");
        require(LEVELS, intention, "intentionLevel", "报名意向");
        require(LEVELS, urgency, "urgencyLevel", "紧急程度");
        require(VERIFICATION_STATUSES, verification, "verificationStatus", "核实状态");
        require(STATUSES, status, "currentStatus", "当前状态");
    }

    public static String normalizeGrade(String value) {
        if (value == null) return null;
        String grade = TextNormalizer.optional(Normalizer.normalize(value, Normalizer.Form.NFKC));
        if (grade == null) return null;
        Matcher full = FULL_GRADE.matcher(grade);
        if (full.matches()) return full.group(1);
        Matcher shortGrade = SHORT_GRADE.matcher(grade);
        if (shortGrade.matches()) return "20" + shortGrade.group(1);
        return grade;
    }

    private static void require(Set<String> allowed, String value, String code, String label) {
        if (!allowed.contains(value)) {
            throw ApiException.badRequest("INVALID_" + code.toUpperCase(), label + "不在允许范围内");
        }
    }
}
