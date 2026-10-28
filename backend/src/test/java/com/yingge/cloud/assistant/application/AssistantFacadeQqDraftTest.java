package com.yingge.cloud.assistant.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.auth.application.CurrentActorProvider;
import com.yingge.cloud.customer.application.CustomerService;
import com.yingge.cloud.customer.infrastructure.CustomerEntity;
import com.yingge.cloud.interaction.application.InteractionService;
import com.yingge.cloud.permission.application.PermissionService;
import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantFacadeQqDraftTest {
    @Mock AiProvider provider;
    @Mock CurrentActorProvider actors;
    @Mock PermissionService permissions;
    @Mock CustomerService customers;
    @Mock CustomerEntity customer;
    @Mock InteractionService interactions;
    @Mock JdbcTemplate jdbc;

    @Test
    void summarizesMinimaxCompatibleWrappedDraftWithoutFallingBackToManualPlaceholder() {
        UUID customerId = UUID.randomUUID();
        CurrentActor actor = new CurrentActor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "member", "成员", Set.of(RoleCode.MEMBER), Set.of(), Set.of());
        when(actors.require()).thenReturn(actor);
        when(customers.requireVisibleEntity(customerId, actor)).thenReturn(customer);
        when(provider.generate(anyString(), eq("[目标客户] 我想了解竞赛"), eq(true)))
                .thenReturn(new AiProvider.AiResult("""
                        <think>internal reasoning</think>
                        {"draft":{"summary":"客户有竞赛意向","customerQuotes":"我想了解竞赛",
                        "nextFollowUpAt":"未确认","uncertainties":[]}}
                        """, 10, 20));
        when(provider.providerId()).thenReturn("openai-compatible");
        when(provider.model()).thenReturn("MiniMax-M3");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        AssistantFacade facade = new AssistantFacade(provider, new ObjectMapper().findAndRegisterModules(), actors,
                permissions, customers, interactions, jdbc);

        var draft = facade.summarizeQqConversation(customerId, "[目标客户] 我想了解竞赛");

        assertThat(draft.summary()).isEqualTo("客户有竞赛意向");
        assertThat(draft.customerQuotes()).containsExactly("我想了解竞赛");
        assertThat(draft.nextFollowUpAt()).isNull();
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        verify(provider).generate(systemPrompt.capture(), eq("[目标客户] 我想了解竞赛"), eq(true));
        assertThat(systemPrompt.getValue()).contains("customerQuotes 和 uncertainties 必须始终为 JSON 数组");
    }
}
