package com.chatbi.security;

import com.chatbi.domain.entity.AppUser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserRolesTest {

    private AppUser user(String roles, Boolean admin) {
        AppUser user = new AppUser();
        user.setUsername("alice");
        user.setRoles(roles);
        user.setIsAdmin(admin);
        return user;
    }

    @Test
    void explicitRolesAreTheSourceOfTruth() {
        assertThat(UserRoles.of(user("ADMIN", false))).containsExactly("ADMIN");
        assertThat(UserRoles.of(user("MANAGER", false))).containsExactly("MANAGER");
        // A demotion must not be undone by a stale legacy flag.
        assertThat(UserRoles.of(user("VIEWER", true))).containsExactly("VIEWER");
    }

    @Test
    void legacyFlagStillAppliesWhenRolesAreBlank() {
        assertThat(UserRoles.of(user(null, true))).containsExactly("ADMIN");
        assertThat(UserRoles.of(user("  ", true))).containsExactly("ADMIN");
    }

    @Test
    void defaultsToViewerWhenNothingIsConfigured() {
        assertThat(UserRoles.of(user(null, false))).containsExactly("VIEWER");
        assertThat(UserRoles.of(user("", null))).containsExactly("VIEWER");
    }
}
