package com.yingge.cloud.business.application;

import com.yingge.cloud.shared.api.ApiException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class BusinessMembershipDirectory {
    private final NamedParameterJdbcTemplate jdbc;

    public BusinessMembershipDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockCustomer(UUID organizationId, UUID customerId) {
        jdbc.queryForObject("""
                select id from customers
                where organization_id=:organizationId and id=:customerId
                for update
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("customerId", customerId), UUID.class);
    }

    public Map<UUID, String> requireEligibleMembers(UUID organizationId, Set<UUID> membershipIds) {
        if (membershipIds.isEmpty()) return Map.of();
        Map<UUID, String> members = new LinkedHashMap<>();
        jdbc.query("""
                select distinct membership.id, account.display_name
                from memberships membership
                join user_accounts account on account.id=membership.user_id and account.enabled=true
                join membership_roles membership_role on membership_role.membership_id=membership.id
                join roles role on role.id=membership_role.role_id
                where membership.organization_id=:organizationId
                  and membership.status='ACTIVE'
                  and membership.id in (:membershipIds)
                  and role.code in ('ORG_ADMIN','TEAM_MANAGER','MEMBER')
                order by account.display_name, membership.id
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("membershipIds", membershipIds), row -> {
                    members.put(row.getObject("id", UUID.class), row.getString("display_name"));
                });
        if (members.size() != membershipIds.size()) {
            throw ApiException.badRequest("BUSINESS_PARTICIPANT_NOT_ELIGIBLE",
                    "主负责人和辅助人员必须是当前组织内已启用且可维护客户的成员");
        }
        return members;
    }

    public boolean hasActiveSecondLine(UUID organizationId, UUID customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                  select 1 from customer_role_assignments
                  where organization_id=:organizationId and customer_id=:customerId
                    and role_type='A_SECOND_LINE' and status='ACTIVE'
                )
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("customerId", customerId), Boolean.class));
    }

    public Set<UUID> notificationRecipients(UUID organizationId, UUID customerId,
                                            Collection<UUID> additionalMembershipIds) {
        LinkedHashSet<UUID> recipients = new LinkedHashSet<>();
        if (additionalMembershipIds != null) recipients.addAll(additionalMembershipIds);
        recipients.addAll(jdbc.queryForList("""
                select membership_id from (
                  select customer.owner_membership_id membership_id
                  from customers customer
                  where customer.organization_id=:organizationId and customer.id=:customerId
                  union
                  select membership.id membership_id
                  from memberships membership
                  join membership_roles membership_role on membership_role.membership_id=membership.id
                  join roles role on role.id=membership_role.role_id and role.code='ORG_ADMIN'
                  join user_accounts account on account.id=membership.user_id and account.enabled=true
                  where membership.organization_id=:organizationId and membership.status='ACTIVE'
                ) recipient
                """, new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("customerId", customerId), UUID.class));
        return Set.copyOf(recipients);
    }
}
