package com.yingge.cloud.customer.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.UUID;

public interface CustomerAccessGrantRepository extends JpaRepository<CustomerAccessGrantEntity, UUID> {
    @Query("""
            select count(g) > 0 from CustomerAccessGrantEntity g
            where g.customerId=:customerId and g.canEdit=true and
            (g.granteeMembershipId=:membershipId or g.granteeTeamId in :teamIds)
            """)
    boolean hasEditGrant(UUID customerId, UUID membershipId, Collection<UUID> teamIds);
}

