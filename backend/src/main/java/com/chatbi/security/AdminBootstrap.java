package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replaces the credentials that V5 seeds for local demos. The migration itself
 * must not change (Flyway checksums), so the replacement happens at startup:
 * with a docker/prod profile, either every seeded demo password is gone and a
 * real admin exists, or {@code CHATBI_BOOTSTRAP_ADMIN_PASSWORD} is required.
 *
 * <p>Only accounts that still carry a known demo hash (or no password) are
 * touched; a password an operator already changed is never overwritten.</p>
 */
@Slf4j
@Component
public class AdminBootstrap implements ApplicationRunner {

    /** BCrypt hashes written by V5 for lisi / zhangsan / wangwu (password ChatBI@2026). */
    static final Set<String> SEEDED_DEMO_HASHES = Set.of(
            "$2b$10$Pv39pSg/8XCPhNLUont1r.E49VjyRU/lnIyj5bBJXYBtvgkp99pTy",
            "$2b$10$dqHE3oy7Sa6uvyrj7rgRlOMim0XzbrC8UTbFvR/WfTB2WgaKWwiAe",
            "$2b$10$VqJXzra3Yfyz1XR7Vw5m4.7AkO5lzZoK1h7xnt66g3nb92RxRkFuO");
    static final String DEMO_PASSWORD = "ChatBI@2026";
    static final int MIN_PASSWORD_LENGTH = 12;

    private static final String ACCOUNTS_SQL = """
            SELECT username, password_hash, is_admin, roles
            FROM app_user
            WHERE enabled = TRUE
            ORDER BY username
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final ChatBiProperties properties;
    private final SecurityStartupChecks startupChecks;

    public AdminBootstrap(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder,
                          ChatBiProperties properties, SecurityStartupChecks startupChecks) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.startupChecks = startupChecks;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!startupChecks.isSharedDeployment()) {
            log.warn("Demo accounts may still use the documented local password; "
                    + "this profile is not for shared or production use.");
            return;
        }
        List<Map<String, Object>> accounts = jdbcTemplate.queryForList(ACCOUNTS_SQL);
        String password = properties.getAuth().getBootstrapAdminPassword();
        boolean hasBootstrap = password != null && !password.isBlank();
        boolean demoCredentialsRemain = accounts.stream()
                .anyMatch(account -> isDemoOrUnset(account.get("password_hash")));
        boolean hasAdmin = accounts.stream().anyMatch(AdminBootstrap::isAdmin);

        if (!hasAdmin) {
            throw new IllegalStateException(
                    "No enabled ADMIN account exists. Set CHATBI_BOOTSTRAP_ADMIN_PASSWORD "
                            + "so the bootstrap admin can be initialized.");
        }
        if (!hasBootstrap) {
            if (demoCredentialsRemain) {
                throw new IllegalStateException(
                        "Seeded demo passwords are still active in a docker/prod deployment. "
                                + "Set CHATBI_BOOTSTRAP_ADMIN_PASSWORD (at least "
                                + MIN_PASSWORD_LENGTH + " characters) and restart.");
            }
            log.info("Production admin credentials already initialized; bootstrap skipped.");
            return;
        }
        validate(password);

        String bootstrapUser = properties.getAuth().getBootstrapAdminUsername();
        Map<String, Object> target = accounts.stream()
                .filter(account -> bootstrapUser.equalsIgnoreCase(asString(account.get("username"))))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Bootstrap admin user does not exist: " + bootstrapUser));
        if (!isAdmin(target)) {
            throw new IllegalStateException(
                    "Bootstrap user is not an ADMIN: " + bootstrapUser);
        }

        Object targetHash = target.get("password_hash");
        if (isDemoOrUnset(targetHash)) {
            String encoded = passwordEncoder.encode(password);
            jdbcTemplate.update("UPDATE app_user SET password_hash = ? WHERE username = ?",
                    encoded, asString(target.get("username")));
            log.info("Initialized the password for bootstrap admin {}.", bootstrapUser);
        } else {
            log.info("Bootstrap admin {} already has a non-demo password; leaving it unchanged.",
                    bootstrapUser);
        }

        for (Map<String, Object> account : accounts) {
            String username = asString(account.get("username"));
            if (username.equalsIgnoreCase(bootstrapUser)) {
                continue;
            }
            if (isSeededDemoHash(account.get("password_hash"))) {
                jdbcTemplate.update("UPDATE app_user SET password_hash = NULL WHERE username = ?",
                        username);
                log.warn("Disabled password login for seeded demo account {}.", username);
            }
        }
    }

    private void validate(String password) {
        if (DEMO_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                    "CHATBI_BOOTSTRAP_ADMIN_PASSWORD must not be the documented demo password.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "CHATBI_BOOTSTRAP_ADMIN_PASSWORD must be at least "
                            + MIN_PASSWORD_LENGTH + " characters.");
        }
    }

    private static boolean isDemoOrUnset(Object hash) {
        String value = asString(hash);
        return value == null || value.isBlank() || SEEDED_DEMO_HASHES.contains(value);
    }

    private static boolean isSeededDemoHash(Object hash) {
        String value = asString(hash);
        return value != null && SEEDED_DEMO_HASHES.contains(value);
    }

    private static boolean isAdmin(Map<String, Object> account) {
        if (Boolean.TRUE.equals(account.get("is_admin"))) {
            return true;
        }
        String roles = asString(account.get("roles"));
        if (roles == null) {
            return false;
        }
        for (String role : roles.split("[,;\\s]+")) {
            if (UserRoles.ADMIN.equals(role.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
