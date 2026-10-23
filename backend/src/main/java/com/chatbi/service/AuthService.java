package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.mapper.AppUserMapper;
import com.chatbi.security.JwtService;
import com.chatbi.security.LoginAttemptGuard;
import com.chatbi.web.UserView;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class AuthService {

    /**
     * A valid BCrypt hash (cost 12, same as the encoder) used for the
     * "user does not exist" path so login timing does not reveal whether a
     * username exists.
     */
    private static final String DUMMY_HASH =
            "$2a$12$aZka3tHkJO/Qo8e7txBfJ.ckGhoQr08HJMuYJ3LFQzKS05WztUdRa";

    /** Minimum length for a password chosen through the API. */
    public static final int MIN_PASSWORD_LENGTH = 12;

    private final UserDirectory userDirectory;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptGuard loginAttemptGuard;

    public AuthService(UserDirectory userDirectory, AppUserMapper userMapper,
                       PasswordEncoder passwordEncoder, JwtService jwtService,
                       LoginAttemptGuard loginAttemptGuard) {
        this.userDirectory = userDirectory;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttemptGuard = loginAttemptGuard;
    }

    public LoginResult login(String username, String password) {
        loginAttemptGuard.checkAllowed(username);
        AppUser user = userDirectory.findByUsername(username);
        if (user == null) {
            // Burn comparable time on the unknown-user path, then fail.
            passwordEncoder.matches(password == null ? "" : password, DUMMY_HASH);
            loginAttemptGuard.onFailure(username);
            throw invalidCredentials();
        }
        if (user.getPasswordHash() == null
                || password == null || password.isBlank()
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            loginAttemptGuard.onFailure(username);
            throw invalidCredentials();
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new ChatBiException("USER_DISABLED", "账号已禁用，请联系管理员", HttpStatus.FORBIDDEN);
        }
        loginAttemptGuard.onSuccess(username);
        user.setLastLoginAt(OffsetDateTime.now());
        userMapper.updateById(user);
        String token = jwtService.issue(user);
        return new LoginResult(token, "Bearer", jwtService.getTtlSeconds(), UserView.from(user));
    }

    /**
     * Self-service password change. Verifying the old password prevents a
     * stolen token from being turned into permanent account takeover.
     */
    public void changePassword(String username, String oldPassword, String newPassword) {
        AppUser user = userDirectory.findByUsername(username);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            throw ChatBiException.unauthorized("登录用户不存在或已禁用");
        }
        if (user.getPasswordHash() == null
                || oldPassword == null || oldPassword.isBlank()
                || !passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw ChatBiException.badRequest("OLD_PASSWORD_INCORRECT", "原密码不正确");
        }
        validateNewPassword(newPassword);
        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw ChatBiException.badRequest("PASSWORD_UNCHANGED", "新密码不能与原密码相同");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
        loginAttemptGuard.onSuccess(username);
    }

    private void validateNewPassword(String newPassword) {
        if (AdminBootstrapPassword.isDemo(newPassword)) {
            throw ChatBiException.badRequest("WEAK_PASSWORD", "不能使用文档中的演示密码");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw ChatBiException.badRequest("WEAK_PASSWORD",
                    "新密码至少需要 " + MIN_PASSWORD_LENGTH + " 个字符");
        }
    }

    private ChatBiException invalidCredentials() {
        return new ChatBiException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
    }

    public record LoginResult(String accessToken, String tokenType, long expiresIn, UserView user) {
    }

    /** Keeps the demo-password constant in one place without exposing it via config. */
    private static final class AdminBootstrapPassword {
        private static boolean isDemo(String value) {
            return "ChatBI@2026".equals(value);
        }
    }
}
