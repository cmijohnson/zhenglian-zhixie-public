package com.yingge.cloud.customer.domain;

import com.yingge.cloud.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerOptionsTest {
    @Test
    void acceptsSupportedLegacyBusinessValues() {
        assertThatCode(() -> CustomerOptions.validate("在校生", "高", "中", "部分待确认", "跟进中"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownWorkflowValues() {
        assertThatThrownBy(() -> CustomerOptions.validate("在校生", "很高", "中", "未核实", "跟进中"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("报名意向");
    }

    @Test
    void normalizesRecognizedGradeFormatsToFourDigitYears() {
        assertThat(CustomerOptions.normalizeGrade("26")).isEqualTo("2026");
        assertThat(CustomerOptions.normalizeGrade("26级")).isEqualTo("2026");
        assertThat(CustomerOptions.normalizeGrade("2026届")).isEqualTo("2026");
        assertThat(CustomerOptions.normalizeGrade("２０２５级")).isEqualTo("2025");
        assertThat(CustomerOptions.normalizeGrade(" 2024 ")).isEqualTo("2024");
    }

    @Test
    void preservesUnknownGradesAndKeepsBlankGradesEmpty() {
        assertThat(CustomerOptions.normalizeGrade("大一")).isEqualTo("大一");
        assertThat(CustomerOptions.normalizeGrade("  ")).isNull();
        assertThat(CustomerOptions.normalizeGrade(null)).isNull();
    }
}
