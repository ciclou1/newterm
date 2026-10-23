package com.chatbi.security;

import com.chatbi.domain.entity.AppUser;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class UserRoles {

    public static final String ADMIN = "ADMIN";
    public static final String MANAGER = "MANAGER";
    public static final String VIEWER = "VIEWER";
    public static final String SERVICE = "SERVICE";

    private static final Set<String> KNOWN = Set.of(ADMIN, MANAGER, VIEWER, SERVICE);

    private UserRoles() {
    }

    /**
     * Resolve effective roles. The {@code roles} column is the source of truth.
     * {@code is_admin} is only a compatibility fallback for rows that predate
     * the column (blank roles); it must not override an explicit demotion such
     * as {@code roles=VIEWER}.
     */
    public static List<String> of(AppUser user) {
        Set<String> roles = new LinkedHashSet<>();
        if (user.getRoles() != null) {
            Arrays.stream(user.getRoles().split("[,;\\s]+"))
                    .map(value -> value.trim().toUpperCase(Locale.ROOT))
                    .filter(value -> !value.isBlank() && KNOWN.contains(value))
                    .forEach(roles::add);
        }
        if (roles.isEmpty() && Boolean.TRUE.equals(user.getIsAdmin())) {
            roles.add(ADMIN);
        }
        if (roles.isEmpty()) {
            roles.add(VIEWER);
        }
        return List.copyOf(roles);
    }

    public static List<GrantedAuthority> authorities(List<String> roles) {
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    public static boolean hasRole(List<String> roles, String role) {
        return roles != null && roles.contains(role);
    }
}
