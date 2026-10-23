package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.mapper.AppUserMapper;
import com.chatbi.security.JwtService;
import com.chatbi.web.UserView;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class AuthService {

    /**
     * A valid BCrypt hash used for the "user does not exist" path so login
     * timing does not reveal whether a username exists.
     */
    private static final String DUMMY_HASH =
            "$2b$10$Pv39pSg/8XCPhNLUont1r.E49VjyRU/lnIyj5bBJXYBtvgkp99pTy";

    private final UserDirectory userDirectory;
    private final AppUserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserDirectory userDirectory, AppUserMapper userMapper,
                       PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userDirectory = userDirectory;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResult login(String username, String password) {
        AppUser user = userDirectory.findByUsername(username);
        if (user == null) {
            // Burn comparable time on the unknown-user path, then fail.
            passwordEncoder.matches(password == null ? "" : password, DUMMY_HASH);
            throw invalidCredentials();
        }
        if (user.getPasswordHash() == null
                || password == null || password.isBlank()
                || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new ChatBiException("USER_DISABLED", "账号已禁用，请联系管理员", HttpStatus.FORBIDDEN);
        }
        user.setLastLoginAt(OffsetDateTime.now());
        userMapper.updateById(user);
        String token = jwtService.issue(user);
        return new LoginResult(token, "Bearer", jwtService.getTtlSeconds(), UserView.from(user));
    }

    private ChatBiException invalidCredentials() {
        return new ChatBiException("INVALID_CREDENTIALS", "用户名或密码错误", HttpStatus.UNAUTHORIZED);
    }

    public record LoginResult(String accessToken, String tokenType, long expiresIn, UserView user) {
    }
}
