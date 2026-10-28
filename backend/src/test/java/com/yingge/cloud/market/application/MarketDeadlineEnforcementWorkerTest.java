package com.yingge.cloud.market.application;

import com.yingge.cloud.customerpool.application.CustomerPoolService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketDeadlineEnforcementWorkerTest {
    @Test
    void abReleaseQueryRequiresExplicitEnabledPolicyAndPostEnableDeadline() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        CustomerPoolService pools = mock(CustomerPoolService.class);
        MarketDeadlineService deadlines = mock(MarketDeadlineService.class);
        when(jdbc.queryForList(anyString(), any(Map.class))).thenReturn(List.of());

        int released = new MarketDeadlineEnforcementWorker(jdbc, pools, deadlines).enforceDueDeadlines();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Map.class));
        assertThat(sql.getValue())
                .contains("policy.ab_auto_release_enabled=true")
                .contains("policy.ab_auto_release_effective_from is not null")
                .contains("snapshot.computed_deadline>=policy.ab_auto_release_effective_from");
        verify(pools, never()).handleDeadlineExpiry(any(), any());
        verify(deadlines).refreshSnapshotsForActiveRules();
        assertThat(released).isZero();
    }
}
