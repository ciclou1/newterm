package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityStartupChecksTest {

    private static final String VALID_SECRET = "startup-check-secret-0123456789abcdef";

    private ChatBiProperties properties(String secret, boolean demoHeader) {
        ChatBiProperties properties = new ChatBiProperties();
        properties.getAuth().setJwtSecret(secret);
        properties.getAuth().setDemoHeaderEnabled(demoHeader);
        properties.getAuth().setServiceToken("service-token");
        return properties;
    }

    private SecurityStartupChecks checks(ChatBiProperties properties, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new SecurityStartupChecks(properties, environment);
    }

    @Test
    void productionRejectsMissingSecret() {
        assertThatThrownBy(() -> checks(properties("", false), "prod").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHATBI_JWT_SECRET");
        assertThatThrownBy(() -> checks(properties("", false), "docker").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHATBI_JWT_SECRET");
    }

    @Test
    void productionRejectsShortSecret() {
        assertThatThrownBy(() -> checks(properties("too-short", false), "prod").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void productionRejectsBundledDevelopmentSecret() {
        assertThatThrownBy(() -> checks(
                properties("local-dev-only-secret-change-me-0123456789abcdef", false), "docker").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development value");
    }

    @Test
    void productionRejectsDemoHeaderEvenWhenEnvironmentOverridesYaml() {
        assertThatThrownBy(() -> checks(properties(VALID_SECRET, true), "prod").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo-header-enabled");
        assertThatThrownBy(() -> checks(properties(VALID_SECRET, true), "docker").check())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demo-header-enabled");
    }

    @Test
    void productionAcceptsAValidSecret() {
        assertThatCode(() -> checks(properties(VALID_SECRET, false), "prod").check())
                .doesNotThrowAnyException();
    }

    @Test
    void localProfileStillStartsWithTheDevelopmentSecretAndDemoHeader() {
        assertThatCode(() -> checks(
                properties("local-dev-only-secret-change-me-0123456789abcdef", true)).check())
                .doesNotThrowAnyException();
    }
}
