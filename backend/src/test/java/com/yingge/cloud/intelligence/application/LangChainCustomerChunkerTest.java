package com.yingge.cloud.intelligence.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangChainCustomerChunkerTest {
    private final LangChainCustomerChunker chunker = new LangChainCustomerChunker();

    @Test
    void createsBoundedOverlappingSegmentsWithoutInventingContent() {
        String source = ("第一阶段已建立联系，客户表示需要了解专业方向。\n").repeat(80)
                + "最终结论：下周再沟通。";

        var segments = chunker.split(source);

        assertThat(segments).hasSizeGreaterThan(1);
        assertThat(segments).allSatisfy(segment -> {
            assertThat(segment.text()).isNotBlank();
            assertThat(segment.text().length()).isLessThanOrEqualTo(1_200);
            assertThat(source).contains(segment.text());
        });
        assertThat(segments.getLast().text()).contains("最终结论");
    }

    @Test
    void skipsBlankDocuments() {
        assertThat(chunker.split("  \n ")).isEmpty();
    }
}
