package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SecurityStartupChecks {

    private static final String DEV_SECRET_PREFIX = "local-dev-only";

    private final ChatBiProperties properties;

    public SecurityStartupChecks(ChatBiProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void check() {
        ChatBiProperties.Auth auth = properties.getAuth();
        if (auth.isDemoHeaderEnabled()) {
            log.warn("SECURITY WARNING: chatbi.auth.demo-header-enabled=true — "
                    + "X-ChatBI-User can create any identity. Never enable this in production.");
        }
        if (auth.getServiceToken() == null || auth.getServiceToken().isBlank()) {
            log.warn("CHATBI_MCP_SERVICE_TOKEN is not configured; service-token authentication is disabled.");
        }
        if (auth.getJwtSecret() != null && auth.getJwtSecret().startsWith(DEV_SECRET_PREFIX)) {
            log.warn("Using the bundled development JWT secret. Set CHATBI_JWT_SECRET before deployment.");
        }
    }
}
