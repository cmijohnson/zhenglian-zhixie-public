package com.yingge.cloud.business.application;

/**
 * System-wide definition of a cross-sale customer.
 *
 * <p>A customer is a cross sale as soon as at least one other active member has
 * a person-level customer access grant. The grant may come from ordinary
 * collaboration, A-class second-line support, or another governed follow-up
 * workflow. Team-wide visibility does not count.</p>
 */
final class CrossSaleDefinition {
    static final String ACTIVE_COLLABORATOR_EXISTS = """
            exists (
              select 1 from customer_access_grants grant_row
              join memberships collaborator_membership
                on collaborator_membership.id=grant_row.grantee_membership_id
               and collaborator_membership.organization_id=grant_row.organization_id
               and collaborator_membership.status='ACTIVE'
              join user_accounts collaborator_account
                on collaborator_account.id=collaborator_membership.user_id
               and collaborator_account.enabled=true
              where grant_row.organization_id=customer.organization_id
                and grant_row.customer_id=customer.id
                and collaborator_membership.id<>customer.owner_membership_id
            )
            """;

    private CrossSaleDefinition() {
    }
}
