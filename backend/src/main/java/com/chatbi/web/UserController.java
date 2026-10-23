package com.chatbi.web;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.service.CurrentUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final CurrentUserService currentUserService;

    public UserController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<UserView> users() {
        return currentUserService.all().stream().map(UserView::from).toList();
    }

    public record UserView(Long id, String username, String displayName, String role,
                           Long departmentId, boolean admin) {
        static UserView from(AppUser user) {
            return new UserView(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                    user.getDepartmentId(), Boolean.TRUE.equals(user.getIsAdmin()));
        }
    }
}
