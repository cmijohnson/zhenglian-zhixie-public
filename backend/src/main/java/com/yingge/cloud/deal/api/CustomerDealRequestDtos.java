package com.yingge.cloud.deal.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CustomerDealRequestDtos {
    private CustomerDealRequestDtos() {}

    public record Response(
            UUID id,
            UUID customerId,
            String customerName,
            String teamName,
            String ownerDisplayName,
            UUID applicantMembershipId,
            String applicantDisplayName,
            String status,
            Instant requestedAt,
            Instant reviewedAt,
            UUID reviewerMembershipId,
            long version
    ) {}

    public record Page(
            List<Response> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
