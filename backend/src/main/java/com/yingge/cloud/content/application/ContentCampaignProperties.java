package com.yingge.cloud.content.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

@ConfigurationProperties("app.content.campaign")
public record ContentCampaignProperties(
        String name,
        LocalDate startDate,
        LocalDate endDate,
        int xiaohongshuTarget,
        int douyinTarget
) {}
