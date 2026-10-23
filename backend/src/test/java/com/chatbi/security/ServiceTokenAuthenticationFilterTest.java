package com.chatbi.security;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.metrics.ChatBiMetrics;
import com.chatbi.service.UserDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServiceTokenAuthenticationFilterTest {

    private final UserDirectory userDirectory = mock(UserDirectory.class);
    private final ChatBiMetrics metrics = mock(ChatBiMetrics.class);
    private final SecurityErrorWriter errorWriter =
            new SecurityErrorWriter(new ObjectMapper().findAndRegisterModules());

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ServiceTokenAuthenticationFilter filter(String configuredToken) {
        return new ServiceTokenAuthenticationFilter(configuredToken, "mcp-service",
                userDirectory, errorWriter, metrics);
    }

    private AppUser serviceUser() {
        AppUser user = new AppUser();
        user.setId(99L);
        user.setUsername("mcp-service");
        user.setDisplayName("MCP Service");
        user.setRoles("ADMIN");
        user.setIsAdmin(true);
        user.setEnabled(true);
        return user;
    }

    @Test
    void missingConfigurationReturnsClearError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/query");
        request.addHeader(ServiceTokenAuthenticationFilter.HEADER, "anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter("").doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SERVICE_TOKEN_NOT_CONFIGURED");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/query");
        request.addHeader(ServiceTokenAuthenticationFilter.HEADER, "wrong");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter("expected-token").doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INVALID_SERVICE_TOKEN");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void validTokenAuthenticatesWithoutAdminRights() throws Exception {
        when(userDirectory.findByUsername("mcp-service")).thenReturn(serviceUser());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/query");
        request.addHeader(ServiceTokenAuthenticationFilter.HEADER, "expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter("expected-token").doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        List<String> authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).toList();
        assertThat(authorities).doesNotContain("ROLE_ADMIN");
        assertThat(authorities).contains("ROLE_VIEWER");
    }

    @Test
    void missingServiceUserIsRejected() throws Exception {
        when(userDirectory.findByUsername("mcp-service")).thenReturn(null);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat/query");
        request.addHeader(ServiceTokenAuthenticationFilter.HEADER, "expected-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter("expected-token").doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SERVICE_PRINCIPAL_UNAVAILABLE");
    }
}
