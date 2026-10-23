package com.chatbi.service;

import com.chatbi.common.ChatBiException;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.mapper.AppUserMapper;
import com.chatbi.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final UserDirectory userDirectory = mock(UserDirectory.class);
    private final AppUserMapper userMapper = mock(AppUserMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final JwtService jwtService = mock(JwtService.class);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userDirectory, userMapper, passwordEncoder, jwtService);
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
    }

    @Test
    void rejectsWrongPassword() {
        AppUser user = user(true, "$2a$hash", "VIEWER");
        when(userDirectory.findByUsername("alice")).thenReturn(user);
        when(passwordEncoder.matches("wrong", "$2a$hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("alice", "wrong"))
                .isInstanceOfSatisfying(ChatBiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS"));
        verify(jwtService, never()).issue(any(AppUser.class));
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
}
