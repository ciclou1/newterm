package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.mapper.AppUserMapper;
import com.chatbi.security.JwtService;
import com.chatbi.security.LoginAttemptGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserDirectory userDirectory = mock(UserDirectory.class);
    private final AppUserMapper userMapper = mock(AppUserMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final LoginAttemptGuard loginAttemptGuard = mock(LoginAttemptGuard.class);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userDirectory, userMapper, passwordEncoder, jwtService,
                loginAttemptGuard);
        when(jwtService.getTtlSeconds()).thenReturn(3600L);
    }

    private AppUser user(boolean enabled, String hash, String roles) {
        AppUser user = new AppUser();
        user.setId(1L);
        user.setUsername("alice");
        user.setDisplayName("Alice");
        user.setEnabled(enabled);
        user.setPasswordHash(hash);
        user.setRoles(roles);
        user.setIsAdmin(false);
        return user;
    }

    @Test
    void logsInWithValidPassword() {
        AppUser user = user(true, "$2a$hash", "MANAGER");
        when(userDirectory.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("secret", "$2a$hash")).thenReturn(true);
        when(jwtService.issue(user)).thenReturn("signed-token");

        AuthService.LoginResult result = authService.login("alice", "secret");

        assertThat(result.accessToken()).isEqualTo("signed-token");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.expiresIn()).isEqualTo(3600L);
        assertThat(result.user().username()).isEqualTo("alice");
        assertThat(result.user().roles()).containsExactly("MANAGER");
        assertThat(user.getLastLoginAt()).isNotNull();
        verify(userMapper).updateById(user);
        verify(loginAttemptGuard).onSuccess("alice");
    }

    @Test
    void rejectsUnknownUser() {
        when(userDirectory.findByUsername("ghost")).thenReturn(null);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.login("ghost", "secret"))
                .isInstanceOfSatisfying(ChatBiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS");
                    assertThat(e.getStatus().value()).isEqualTo(401);
                });
        verify(jwtService, never()).issue(any(AppUser.class));
        verify(loginAttemptGuard).onFailure("ghost");
    }

    @Test
    void rejectsWrongPasswordAndCountsTheFailure() {
        AppUser user = user(true, "$2a$hash", "VIEWER");
        when(userDirectory.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("alice", "wrong"))
                .isInstanceOfSatisfying(ChatBiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS"));
        verify(jwtService, never()).issue(any(AppUser.class));
        verify(loginAttemptGuard).onFailure("alice");
    }

    @Test
    void rejectsLoginWhileTheAccountIsLocked() {
        doThrow(ChatBiException.tooManyRequests("LOGIN_TEMPORARILY_LOCKED", "locked"))
                .when(loginAttemptGuard).checkAllowed("alice");

        assertThatThrownBy(() -> authService.login("alice", "secret"))
                .isInstanceOfSatisfying(ChatBiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("LOGIN_TEMPORARILY_LOCKED"));
    }

    @Test
    void rejectsAccountWithoutPasswordHash() {
        AppUser user = user(true, null, "VIEWER");
        when(userDirectory.findByUsername("alice")).thenReturn(user);

        assertThatThrownBy(() -> authService.login("alice", "ChatBI@2026"))
                .isInstanceOfSatisfying(ChatBiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS"));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtService, never()).issue(any(AppUser.class));
    }

    @Test
    void rejectsDisabledAccountAfterPasswordCheck() {
        AppUser user = user(false, "$2a$hash", "VIEWER");
        when(userDirectory.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("secret", "$2a$hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login("alice", "secret"))
                .isInstanceOfSatisfying(ChatBiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("USER_DISABLED");
                    assertThat(e.getStatus().value()).isEqualTo(403);
                });
        verify(userMapper, never()).updateById(any(AppUser.class));
        verify(jwtService, never()).issue(any(AppUser.class));
    }

    @Test
    void rejectsBlankPassword() {
        AppUser user = user(true, "$2a$hash", "VIEWER");
        when(userDirectory.findByUsername("alice")).thenReturn(user);

        assertThatThrownBy(() -> authService.login("alice", " "))
                .isInstanceOfSatisfying(ChatBiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS"));
    }

    @Test
    void changesPasswordAfterVerifyingTheOldOne() {
        AppUser user = user(true, "$2a$old", "VIEWER");
        when(userDirectory.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("old-password-1", "$2a$old")).thenReturn(true);
        when(passwordEncoder.matches("new-password-1", "$2a$old")).thenReturn(false);
        when(passwordEncoder.encode("new-password-1")).thenReturn("$2a$new");

        authService.changePassword("alice", "old-password-1", "new-password-1");

        assertThat(user.getPasswordHash()).isEqualTo("$2a$new");
        verify(userMapper).updateById(user);
    }

    @Test
    void rejectsPasswordChangeWithAWrongOldPassword() {
        AppUser user = user(true, "$2a$old", "VIEWER");
        when(userDirectory.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("wrong", "$2a$old")).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword("alice", "wrong", "new-password-1"))
                .isInstanceOfSatisfying(ChatBiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OLD_PASSWORD_INCORRECT"));
        verify(userMapper, never()).updateById(any(AppUser.class));
    }

    @Test
    void rejectsWeakOrUnchangedPassword() {
        AppUser user = user(true, "$2a$old", "VIEWER");
        when(userDirectory.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("old-password-1", "$2a$old")).thenReturn(true);
        when(passwordEncoder.matches("new-password-1", "$2a$old")).thenReturn(true);

        assertThatThrownBy(() -> authService.changePassword("alice", "old-password-1", "short"))
                .isInstanceOfSatisfying(ChatBiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("WEAK_PASSWORD"));
        assertThatThrownBy(() -> authService.changePassword("alice", "old-password-1", "ChatBI@2026"))
                .isInstanceOfSatisfying(ChatBiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("WEAK_PASSWORD"));
        assertThatThrownBy(() -> authService.changePassword("alice", "old-password-1", "new-password-1"))
                .isInstanceOfSatisfying(ChatBiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("PASSWORD_UNCHANGED"));
    }
}
