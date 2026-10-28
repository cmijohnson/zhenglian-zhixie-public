package com.yingge.cloud.business.api;

import com.yingge.cloud.business.domain.BusinessCycleStatus;

import java.time.Instant;
import java.util.UUID;

public final class CrossSaleDtos {
    private CrossSaleDtos() {
    }

    public record Response(
            UUID customerId,
            String customerName,
            String customerQuality,
            UUID teamId,
            String teamName,
            UUID ownerMembershipId,
            String ownerDisplayName,
            int collaboratorCount,
            String collaboratorNames,
            boolean crossSale,
            UUID businessCycleId,
            Integer businessCycleNumber,
            String businessCycleTitle,
            BusinessCycleStatus businessCycleStatus,
            String quoteType,
            Instant quotedAt,
            String competitorName,
            String competitorFact,
            Instant statusMarkedAt,
            Instant customerUpdatedAt
    ) {
    }
}
