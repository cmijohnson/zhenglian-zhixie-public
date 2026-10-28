package com.yingge.cloud.business.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerReferralRepository extends JpaRepository<CustomerReferralEntity, UUID> {
    Optional<CustomerReferralEntity> findByOrganizationIdAndCreatedByMembershipIdAndIdempotencyKeyHash(
            UUID organizationId, UUID membershipId, String idempotencyKeyHash);
    List<CustomerReferralEntity> findByOrganizationIdAndReferrerCustomerIdOrderByCreatedAtDesc(
            UUID organizationId, UUID referrerCustomerId);
}
