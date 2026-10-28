package com.yingge.cloud.auth.infrastructure;

import com.yingge.cloud.permission.domain.CurrentActor;
import com.yingge.cloud.permission.domain.RoleCode;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public class CloudPrincipal implements UserDetails {
    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID userId;
    private final UUID membershipId;
    private final UUID organizationId;
    private final String username;
    private final String displayName;
    private final String password;
    private final boolean passwordChangeRequired;
    private final Set<RoleCode> roles;
    private final Set<UUID> teamIds;
    private final Set<UUID> managedTeamIds;
    private final Collection<? extends GrantedAuthority> authorities;

    public CloudPrincipal(
            UUID userId,
            UUID membershipId,
            UUID organizationId,
            String username,
            String displayName,
            String password,
            boolean passwordChangeRequired,
            Set<RoleCode> roles,
            Set<UUID> teamIds,
            Set<UUID> managedTeamIds
    ) {
        this.userId = userId;
        this.membershipId = membershipId;
        this.organizationId = organizationId;
        this.username = username;
        this.displayName = displayName;
        this.password = password;
        this.passwordChangeRequired = passwordChangeRequired;
        this.roles = Set.copyOf(roles);
        this.teamIds = Set.copyOf(teamIds);
        this.managedTeamIds = Set.copyOf(managedTeamIds);
        this.authorities = roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
    }

    public CurrentActor actor() {
        return new CurrentActor(userId, membershipId, organizationId, username, displayName,
                roles, teamIds, managedTeamIds == null ? Set.of() : managedTeamIds);
    }

    public boolean passwordChangeRequired() { return passwordChangeRequired; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}
