package com.yingge.cloud.feishu.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeishuDealBotEventServiceTest {
    @Test
    void usesConfiguredTenantWhenMessageSenderOmitsTenant() {
        assertThat(FeishuDealBotEventService.resolveEventTenantKey(null, null, "tenant-a"))
                .isEqualTo("tenant-a");
        assertThat(FeishuDealBotEventService.resolveEventTenantKey(" ", "tenant-a", "tenant-a"))
                .isEqualTo("tenant-a");
    }

    @Test
    void rejectsConflictingEventTenant() {
        assertThat(FeishuDealBotEventService.resolveEventTenantKey("tenant-b", "tenant-b", "tenant-a"))
                .isNull();
        assertThat(FeishuDealBotEventService.resolveEventTenantKey("tenant-a", "tenant-b", "tenant-a"))
                .isNull();
    }

    @Test
    void matchesExactUserOpenOrUnionIdentityWithoutUsingDisplayData() {
        assertThat(FeishuDealBotEventService.identitiesMatch(
                "user-a", "open-a", "union-a", "user-a", null, null)).isTrue();
        assertThat(FeishuDealBotEventService.identitiesMatch(
                "user-a", "open-a", "union-a", null, "open-a", null)).isTrue();
        assertThat(FeishuDealBotEventService.identitiesMatch(
                "user-a", "open-a", "union-a", null, null, "union-a")).isTrue();
        assertThat(FeishuDealBotEventService.identitiesMatch(
                "user-a", "open-a", "union-a", "user-b", "open-b", "union-b")).isFalse();
        assertThat(FeishuDealBotEventService.identitiesMatch(
                null, null, null, null, null, null)).isFalse();
    }

    @Test
    void toleratesSdkIdentityFieldVariationOnlyWhenAnExactStableValueMatches() {
        assertThat(FeishuDealBotEventService.identitiesMatch(
                "user-a", "open-a", null, "open-a", null, null)).isTrue();
        assertThat(FeishuDealBotEventService.identitiesMatch(
                "user-a", "open-a", null, "成员甲", null, null)).isFalse();
    }

    @Test
    void appendsSchemaV2ButtonsDirectlyWithoutLegacyActionWrapper() {
        List<Map<String, Object>> elements = new ArrayList<>();
        Map<String, Object> start = Map.of("tag", "button", "value", Map.of("action", "start_deal"));
        Map<String, Object> cancel = Map.of("tag", "button", "value", Map.of("action", "cancel_deal"));

        FeishuDealBotEventService.appendCardButtons(elements, List.of(start, cancel));

        assertThat(elements).containsExactly(start, cancel);
        assertThat(elements).allSatisfy(element -> assertThat(element.get("tag")).isEqualTo("button"));
    }

    @Test
    void parsesCompositeSlashCommandWithoutGuessingUnknownFields() {
        assertThat(FeishuDealBotSlashService.parseSetArguments(
                "primary=成员甲 assist=成员乙,成员丙 order=3000.50 bonus=500 split=成员甲:300,成员乙:200"))
                .containsEntry("primary", "成员甲")
                .containsEntry("assist", "成员乙,成员丙")
                .containsEntry("order", "3000.50")
                .containsEntry("bonus", "500")
                .containsEntry("split", "成员甲:300,成员乙:200");
    }

    @Test
    void rejectsUnknownOrRepeatedCompositeFields() {
        assertThatThrownBy(() -> FeishuDealBotSlashService.parseSetArguments("owner=成员甲"))
                .hasMessageContaining("只支持");
        assertThatThrownBy(() -> FeishuDealBotSlashService.parseSetArguments("bonus=300 bonus=500"))
                .hasMessageContaining("不能重复");
    }

    @Test
    void routesChineseCompatibilityCommandsThroughTheSlashStateMachine() {
        assertThat(FeishuDealBotEventService.legacySlash("下一页")).isEqualTo("/next");
        assertThat(FeishuDealBotEventService.legacySlash("成单")).isEqualTo("/deal");
        assertThat(FeishuDealBotEventService.legacySlash("打开CRM")).isEqualTo("/crm");
        assertThat(FeishuDealBotEventService.legacySlash("客户系统")).isEqualTo("/crm");
        assertThat(FeishuDealBotEventService.legacySlash("打开成单页面")).isEqualTo("/deal-web");
        assertThat(FeishuDealBotEventService.legacySlash("修改 成单人 成员甲")).isEqualTo("/primary 成员甲");
        assertThat(FeishuDealBotEventService.legacySlash("修改 协助 成员乙 成员丙"))
                .isEqualTo("/assist 成员乙 成员丙");
        assertThat(FeishuDealBotEventService.legacySlash("修改 订单 3000.50"))
                .isEqualTo("/order 3000.50");
        assertThat(FeishuDealBotEventService.legacySlash("修改 奖金 500"))
                .isEqualTo("/bonus 500");
        assertThat(FeishuDealBotEventService.legacySlash("修改 分配 成员甲=300 成员乙=200"))
                .isEqualTo("/split 成员甲=300 成员乙=200");
        assertThat(FeishuDealBotEventService.legacySlash("普通聊天")).isNull();
    }

    @Test
    void countsEveryRevisionAfterTheDraftFirstBecomesComplete() {
        Instant firstCompleted = Instant.parse("2026-09-06T12:00:00Z");
        assertThat(FeishuDealBotSlashService.nextEditCount(null, 0)).isZero();
        assertThat(FeishuDealBotSlashService.nextEditCount(firstCompleted, 0)).isEqualTo(1);
        assertThat(FeishuDealBotSlashService.canRevise(firstCompleted, 2)).isTrue();
        assertThat(FeishuDealBotSlashService.canRevise(firstCompleted, 3)).isFalse();
    }
}
