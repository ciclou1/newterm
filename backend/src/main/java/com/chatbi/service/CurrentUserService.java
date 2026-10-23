package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.security.ChatBiPrincipal;
import com.chatbi.security.UserRoles;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CurrentUserService {

    private final UserDirectory userDirectory;

    public CurrentUserService(UserDirectory userDirectory) {
        this.userDirectory = userDirectory;
    }

    /**
     * Resolve the authenticated principal from the SecurityContext. There is no
     * default user: an anonymous request is always rejected.
     */
    public AppUser getCurrent() {
        ChatBiPrincipal principal = principal();
        AppUser user = userDirectory.findByUsername(principal.username());
        if (user == null) {
            throw new ChatBiException("USER_NOT_FOUND", "登录用户不存在，请重新登录",
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw ChatBiException.forbidden("USER_DISABLED", "账号已禁用，请联系管理员");
        }
        return user;
    }

    public String currentUsername() {
        return principal().username();
    }

    public boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if ("ROLE_ADMIN".equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw ChatBiException.forbidden("ADMIN_REQUIRED", "该操作需要平台管理员权限");
        }
    }

    public AppUser requireAdminUser() {
        AppUser user = getCurrent();
        requireAdmin();
        return user;
    }

    public List<AppUser> all() {
        return userDirectory.enabledUsers();
    }

    private ChatBiPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw ChatBiException.unauthorized("请先登录");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof ChatBiPrincipal chatBiPrincipal) {
            return chatBiPrincipal;
        }
        if (principal instanceof String username && !username.isBlank()
                && !"anonymousUser".equals(username)) {
            return new ChatBiPrincipal(username, null, username, List.of(UserRoles.VIEWER));
        }
        throw ChatBiException.unauthorized("请先登录");
    }
}
