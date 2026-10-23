package com.chatbi.web;

import com.chatbi.service.AuthService;
import com.chatbi.service.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;

    public AuthController(AuthService authService, CurrentUserService currentUserService) {
        this.authService = authService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/login")
    public AuthService.LoginResult login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.username(), request.password());
    }

    @GetMapping("/me")
    public UserView me() {
        return UserView.from(currentUserService.getCurrent());
    }

    /**
     * Self-service password change. Requires the current password so a stolen
     * token alone cannot lock the owner out of their account.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(currentUserService.currentUsername(),
                request.oldPassword(), request.newPassword());
    }

    public record LoginRequest(@NotBlank(message = "用户名不能为空")
                               @Size(max = 64, message = "用户名过长") String username,
                               @NotBlank(message = "密码不能为空")
                               @Size(max = 128, message = "密码过长") String password) {
    }

    public record ChangePasswordRequest(@NotBlank(message = "原密码不能为空")
                                        @Size(max = 128, message = "原密码过长") String oldPassword,
                                        @NotBlank(message = "新密码不能为空")
                                        @Size(max = 128, message = "新密码过长") String newPassword) {
    }
}
