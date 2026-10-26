package com.chatbi.security;

import java.security.Principal;
import java.util.List;

/**
 * Authenticated principal stored in the SecurityContext. Controllers resolve
 * the full {@code app_user} row from the database, but authorization decisions
 * use these roles because they are the roles that were actually signed into
 * the presented token.
 */
public record ChatBiPrincipal(String username, Long userId, String displayName, List<String> roles,
                              String passwordFingerprint) implements Principal {

    /**
     * Principals that do not come from a password login (demo header, service
     * token, error paths) carry no fingerprint.
     */
    public ChatBiPrincipal(String username, Long userId, String displayName, List<String> roles) {
        this(username, userId, displayName, roles, null);
    }

    public ChatBiPrincipal {
        roles = List.copyOf(roles);
    }

    @Override
    public String getName() {
        return username;
    }
}
