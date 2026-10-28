package com.yingge.cloud.mobile.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MobileAssistantTimeBandTest {
    @Test
    void mapsAllFiveBusinessGreetingPeriods() {
        assertThat(MobileAssistantService.TimeBand.at(5).salutation()).isEqualTo("早上好");
        assertThat(MobileAssistantService.TimeBand.at(10).salutation()).isEqualTo("早上好");
        assertThat(MobileAssistantService.TimeBand.at(11).salutation()).isEqualTo("中午好");
        assertThat(MobileAssistantService.TimeBand.at(13).salutation()).isEqualTo("中午好");
        assertThat(MobileAssistantService.TimeBand.at(14).salutation()).isEqualTo("下午好");
        assertThat(MobileAssistantService.TimeBand.at(17).salutation()).isEqualTo("下午好");
        assertThat(MobileAssistantService.TimeBand.at(18).salutation()).isEqualTo("晚上好");
        assertThat(MobileAssistantService.TimeBand.at(22).salutation()).isEqualTo("晚上好");
        assertThat(MobileAssistantService.TimeBand.at(23).salutation()).isEqualTo("夜深了");
        assertThat(MobileAssistantService.TimeBand.at(4).salutation()).isEqualTo("夜深了");
    }
}
