package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {

    private static final String DEMO_HASH =
            "$2b$10$Pv39pSg/8XCPhNLUont1r.E49VjyRU/lnIyj5bBJXYBtvgkp99pTy";
    private static final String OTHER_DEMO_HASH =
            "$2b$10$dqHE3oy7Sa6uvyrj7rgRlOMim0XzbrC8UTbFvR/WfTB2WgaKWwiAe";
    private static final String REAL_HASH = "$2a$12$replacedByOperatorHashValue000000000000000000000000000000";
    private static final String NEW_PASSWORD = "Bootstrap-Pass-2026";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SecurityStartupChecks startupChecks = mock(SecurityStartupChecks.class);
    private ChatBiProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ChatBiProperties();
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("$2a$12$encodedBootstrapHash");
    }

    private AdminBootstrap bootstrap() {
        return new AdminBootstrap(jdbcTemplate, passwordEncoder, properties, startupChecks);
    }

    private void accounts(Map<String, Object>... rows) {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(rows));
    }

    private Map<String, Object> account(String username, String hash, boolean admin) {
        return Map.of("username", username, "password_hash", hash == null ? "" : hash,
                "is_admin", admin, "roles", admin ? "ADMIN" : "VIEWER");
    }

    @Test
    void doesNothingOutsideSharedDeployments() {
        when(startupChecks.isSharedDeployment()).thenReturn(false);

        bootstrap().run(null);

        verifyNoInteractions(jdbcTemplate, passwordEncoder);
    }

    @Test
    void failsWhenNoAdminAccountExists() {
        when(startupChecks.isSharedDeployment()).thenReturn(true);
        accounts(account("zhangsan", REAL_HASH, false));

        assertThatThrownBy(() -> bootstrap().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN");
    }

    @Test
    void failsWhenDemoPasswordsRemainAndNoBootstrapPasswordIsSet() {
        when(startupChecks.isSharedDeployment()).thenReturn(true);
        accounts(account("lisi", DEMO_HASH, true));

        assertThatThrownBy(() -> bootstrap().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHATBI_BOOTSTRAP_ADMIN_PASSWORD");
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void replacesDemoPasswordAndDisablesOtherSeededAccounts() {
        when(startupChecks.isSharedDeployment()).thenReturn(true);
        properties.getAuth().setBootstrapAdminPassword(NEW_PASSWORD);
        accounts(account("lisi", DEMO_HASH, true), account("zhangsan", OTHER_DEMO_HASH, false));

        bootstrap().run(null);

        verify(jdbcTemplate).update(contains("password_hash = ?"), eq("$2a$12$encodedBootstrapHash"), eq("lisi"));
        verify(jdbcTemplate).update(contains("password_hash = NULL"), eq("zhangsan"));
    }

    @Test
    void neverOverwritesAnOperatorChosenAdminPassword() {
        when(startupChecks.isSharedDeployment()).thenReturn(true);
        properties.getAuth().setBootstrapAdminPassword(NEW_PASSWORD);
        accounts(account("lisi", REAL_HASH, true));

        bootstrap().run(null);

        verify(passwordEncoder, never()).encode(anyString());
        verify(jdbcTemplate, never()).update(contains("password_hash = ?"), anyString(), anyString());
    }

    @Test
    void acceptsARealAdminPasswordWithoutBootstrap() {
        when(startupChecks.isSharedDeployment()).thenReturn(true);
        accounts(account("lisi", REAL_HASH, true));

        assertThatCode(() -> bootstrap().run(null)).doesNotThrowAnyException();
        verify(jdbcTemplate, never()).update(contains("password_hash"), anyString(), anyString());
    }

    @Test
    void rejectsWeakOrDemoBootstrapPasswords() {
        when(startupChecks.isSharedDeployment()).thenReturn(true);
        accounts(account("lisi", DEMO_HASH, true));

        properties.getAuth().setBootstrapAdminPassword("short");
        assertThatThrownBy(() -> bootstrap().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least");

        properties.getAuth().setBootstrapAdminPassword(AdminBootstrap.DEMO_PASSWORD);
        assertThatThrownBy(() -> bootstrap().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo password");
    }
}
