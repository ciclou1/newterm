package com.chatbi.security;

import java.security.Principal;
import java.util.List;

/**
 * Authenticated principal stored in the SecurityContext. Controllers resolve
 * the full {@code app_user} row from the database, but authorization decisions
 * use these roles because they are the roles that were actually signed into
 * the presented token.
 */
public record ChatBiPrincipal(String username, Long userId, String displayName, List<String> roles)
        implements Principal {

    public ChatBiPrincipal {
        roles = List.copyOf(roles);
    }

    @Override
    public String getName() {
        return username;
    }
}
