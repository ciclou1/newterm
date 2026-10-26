package com.chatbi.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsScrapeTokenFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private Authentication run(String configured, String path, String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (header != null) {
            request.addHeader(MetricsScrapeTokenFilter.AUTHORIZATION, header);
        }
        new MetricsScrapeTokenFilter(configured)
                .doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void authenticatesOnlyThePrometheusPathWithTheConfiguredBearer() throws Exception {
        Authentication auth = run("scrape-secret", MetricsScrapeTokenFilter.PATH,
                "Bearer scrape-secret");

        assertThat(auth).isNotNull();
        assertThat(auth.getAuthorities())
                .extracting("authority").containsExactly("ROLE_METRICS");
    }

    @Test
    void sameTokenCannotAuthenticateOtherActuatorPaths() throws Exception {
        Authentication auth = run("scrape-secret", "/actuator/env", "Bearer scrape-secret");

        assertThat(auth).isNull();
    }

    @Test
    void wrongTokenOrConnectionIsLeftAnonymous() throws Exception {
        assertThat(run("scrape-secret", MetricsScrapeTokenFilter.PATH, "Bearer other")).isNull();
        assertThat(run("scrape-secret", MetricsScrapeTokenFilter.PATH, null)).isNull();
        assertThat(run("", MetricsScrapeTokenFilter.PATH, "Bearer anything")).isNull();
    }
}
