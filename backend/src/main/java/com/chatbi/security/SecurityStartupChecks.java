package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Fail-fast checks for settings that are only safe in an isolated demo. The
 * docker and prod profiles are treated as shared deployments: a bundled demo
 * secret or the forgeable X-ChatBI-User header stops startup instead of logging
 * a warning that is easy to miss.
 */
@Slf4j
@Component
public class SecurityStartupChecks {

    static final String DEV_SECRET_PREFIX = "local-dev-only";
    private static final int MIN_SECRET_BYTES = 32;

    private final ChatBiProperties properties;
    private final Environment environment;

    public SecurityStartupChecks(ChatBiProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void check() {
        ChatBiProperties.Auth auth = properties.getAuth();
        boolean sharedDeployment = isSharedDeployment();
        if (sharedDeployment) {
            requireProductionSecret(auth);
            if (auth.isDemoHeaderEnabled()) {
                throw new IllegalStateException(
                        "chatbi.auth.demo-header-enabled=true is not allowed with the docker/prod profile: "
                                + "X-ChatBI-User would let any caller impersonate any account. "
                                + "Set CHATBI_DEMO_HEADER_ENABLED=false.");
            }
        } else if (auth.getJwtSecret() != null && auth.getJwtSecret().startsWith(DEV_SECRET_PREFIX)) {
            log.warn("Using the bundled development JWT secret. Set CHATBI_JWT_SECRET before deployment.");
        }
        if (auth.isDemoHeaderEnabled()) {
            log.warn("SECURITY WARNING: chatbi.auth.demo-header-enabled=true — "
                    + "X-ChatBI-User can create any identity. Never enable this in production.");
        }
        if (auth.getServiceToken() == null || auth.getServiceToken().isBlank()) {
            log.warn("CHATBI_MCP_SERVICE_TOKEN is not configured; service-token authentication is disabled.");
        }
    }

    public boolean isSharedDeployment() {
        for (String profile : environment.getActiveProfiles()) {
            if ("docker".equalsIgnoreCase(profile) || "prod".equalsIgnoreCase(profile)
                    || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private void requireProductionSecret(ChatBiProperties.Auth auth) {
        String secret = auth.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "CHATBI_JWT_SECRET must be set for the docker/prod profile. "
                            + "Generate one with: openssl rand -base64 48");
        }
        if (secret.startsWith(DEV_SECRET_PREFIX)) {
            throw new IllegalStateException(
                    "CHATBI_JWT_SECRET still uses the bundled development value. "
                            + "Generate a new one with: openssl rand -base64 48");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "CHATBI_JWT_SECRET is too short: HS256 requires at least "
                            + MIN_SECRET_BYTES + " bytes");
        }
    }
}
