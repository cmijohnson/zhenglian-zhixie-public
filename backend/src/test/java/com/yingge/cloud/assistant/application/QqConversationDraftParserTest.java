package com.yingge.cloud.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QqConversationDraftParserTest {
    private final QqConversationDraftParser parser = new QqConversationDraftParser(
            new ObjectMapper().findAndRegisterModules());

    @Test
    void parsesStrictDraftWithoutChangingVerifiedContent() {
        var draft = parser.parse("""
                {"summary":"客户确认参加活动","customerQuotes":["我会参加"],
                 "goalsMotivation":"了解竞赛","actionsTaken":"已报名","mainConcerns":"未确认",
                 "decisionParticipants":"未确认","productFit":"活动适配","suggestedFocus":"确认时间",
                 "explicitTaboos":"未确认","outcome":"继续推进","nextStep":"发送安排",
                 "nextFollowUpAt":"2026-08-19T02:00:00Z","uncertainties":[]}
                """);

        assertThat(draft.summary()).isEqualTo("客户确认参加活动");
        assertThat(draft.customerQuotes()).containsExactly("我会参加");
        assertThat(draft.nextFollowUpAt()).isEqualTo(Instant.parse("2026-08-19T02:00:00Z"));
        assertThat(draft.uncertainties()).isEmpty();
    }

    @Test
    void acceptsMinimaxReasoningFenceWrapperAndSingleValueLists() {
        var draft = parser.parse("""
                <think>这里是模型内部分析，不应进入草稿。</think>
                ```json
                {"result":{"summary":"已沟通竞赛意向","customerQuotes":"我想了解一下",
                 "outcome":"待继续确认","nextStep":"发送活动介绍","nextFollowUpAt":"",
                 "uncertainties":null}}
                ```
                """);

        assertThat(draft.summary()).isEqualTo("已沟通竞赛意向");
        assertThat(draft.customerQuotes()).containsExactly("我想了解一下");
        assertThat(draft.goalsMotivation()).isEqualTo("未确认");
        assertThat(draft.nextFollowUpAt()).isNull();
        assertThat(draft.uncertainties()).contains("模型未返回完整结构，请人工核实未确认字段");
    }

    @Test
    void convertsInvalidDateToManualUncertaintyInsteadOfRejectingWholeDraft() {
        var draft = parser.parse("""
                下面是结果：{"summary":"沟通完成","customerQuotes":[{"quote":"好的"}],
                "nextFollowUpAt":"明天下午","uncertainties":[]}
                """);

        assertThat(draft.customerQuotes()).containsExactly("好的");
        assertThat(draft.nextFollowUpAt()).isNull();
        assertThat(draft.uncertainties())
                .contains("模型返回的建议跟进时间无法识别，已留空，请人工确认");
    }

    @Test
    void rejectsUnrelatedOrNonJsonProviderContent() {
        assertThatThrownBy(() -> parser.parse("模型暂时无法处理"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("{\"message\":\"done\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
