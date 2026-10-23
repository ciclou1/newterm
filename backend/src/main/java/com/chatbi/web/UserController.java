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
        currentUserService.requireAdmin();
        return currentUserService.all().stream().map(UserView::from).toList();
    }
}
