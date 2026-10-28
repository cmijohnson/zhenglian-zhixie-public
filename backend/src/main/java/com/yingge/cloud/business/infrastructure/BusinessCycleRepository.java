package com.yingge.cloud.business.infrastructure;

import com.yingge.cloud.business.domain.BusinessCycleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessCycleRepository extends JpaRepository<BusinessCycleEntity, UUID>,
        JpaSpecificationExecutor<BusinessCycleEntity> {
    Optional<BusinessCycleEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);
    List<BusinessCycleEntity> findByOrganizationIdAndCustomerIdOrderByCycleNumberDesc(
            UUID organizationId, UUID customerId);
    Optional<BusinessCycleEntity> findByOrganizationIdAndCreatedByMembershipIdAndIdempotencyKeyHash(
            UUID organizationId, UUID membershipId, String idempotencyKeyHash);
    boolean existsByOrganizationIdAndCustomerIdAndStatusIn(
            UUID organizationId, UUID customerId, List<BusinessCycleStatus> statuses);

    @Query("select coalesce(max(c.cycleNumber),0) from BusinessCycleEntity c " +
            "where c.organizationId=:organizationId and c.customerId=:customerId")
    int maxCycleNumber(UUID organizationId, UUID customerId);
}
