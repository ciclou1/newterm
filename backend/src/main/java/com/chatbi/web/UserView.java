package com.chatbi.web;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.security.UserRoles;

import java.util.List;

public record UserView(Long id, String username, String displayName, String role,
                       List<String> roles, Long departmentId, boolean admin, boolean enabled) {

    public static UserView from(AppUser user) {
        List<String> roles = UserRoles.of(user);
        return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                roles, user.getDepartmentId(), roles.contains(UserRoles.ADMIN),
                Boolean.TRUE.equals(user.getEnabled()));
    }
}
