package com.yingge.cloud.migration.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.migration")
public record MigrationProperties(
        String sqlitePath,
        String sourceKey,
        String organizationId,
        String ownerMembershipId,
        boolean dryRun,
        int batchSize,
        String backupDir,
        String reportDir
) {
}

