package com.yingge.cloud.auth.infrastructure;

import com.yingge.cloud.organization.infrastructure.MembershipRepository;
import com.yingge.cloud.organization.infrastructure.UserAccountRepository;
import com.yingge.cloud.permission.domain.RoleCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CloudUserDetailsService implements UserDetailsService {
    private final UserAccountRepository users;
    private final MembershipRepository memberships;
    private final JdbcTemplate jdbc;

    public CloudUserDetailsService(UserAccountRepository users, MembershipRepository memberships, JdbcTemplate jdbc) {
        this.users = users;
        this.memberships = memberships;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var user = users.findByUsernameIgnoreCase(username)
                .filter(candidate -> candidate.isEnabled())
                .orElseThrow(() -> new UsernameNotFoundException("用户名或密码错误"));
        var membership = memberships.findFirstByUserIdAndStatus(user.getId(), "ACTIVE")
                .orElseThrow(() -> new UsernameNotFoundException("账户未加入可用组织"));

        return principal(user, membership);
    }

    @Transactional(readOnly = true)
    public CloudPrincipal loadByMembershipId(UUID membershipId) throws UsernameNotFoundException {
        var membership = memberships.findById(membershipId)
                .filter(candidate -> "ACTIVE".equals(candidate.getStatus()))
                .orElseThrow(() -> new UsernameNotFoundException("成员账户已失效"));
        var user = users.findById(membership.getUserId())
                .filter(candidate -> candidate.isEnabled())
                .orElseThrow(() -> new UsernameNotFoundException("成员账户已停用"));
        return principal(user, membership);
    }

    private CloudPrincipal principal(
            com.yingge.cloud.organization.infrastructure.UserAccountEntity user,
            com.yingge.cloud.organization.infrastructure.MembershipEntity membership
    ) {

        Set<RoleCode> roles = jdbc.queryForList(
                        "select r.code from roles r join membership_roles mr on mr.role_id=r.id where mr.membership_id=?",
                        String.class,
                        membership.getId()
                ).stream().map(RoleCode::valueOf).collect(Collectors.toUnmodifiableSet());
        Set<UUID> teamIds = Set.copyOf(jdbc.queryForList(
                "select tm.team_id from team_members tm join teams t on t.id=tm.team_id where tm.membership_id=? and t.active=true",
                UUID.class,
                membership.getId()
        ));
        Set<UUID> managedTeamIds = Set.copyOf(jdbc.queryForList(
                "select tm.team_id from team_members tm join teams t on t.id=tm.team_id where tm.membership_id=? and tm.is_manager=true and t.active=true",
                UUID.class,
                membership.getId()
        ));

        return new CloudPrincipal(
                user.getId(), membership.getId(), membership.getOrganizationId(), user.getUsername(),
                user.getDisplayName(), user.getPasswordHash(), user.isPasswordChangeRequired(),
                roles, teamIds, managedTeamIds
        );
    }
}
