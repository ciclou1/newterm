package com.chatbi.security;

import com.chatbi.mapper.AppUserMapper;
import com.chatbi.metrics.ChatBiMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * When the backend is only reachable through a trusted proxy that overwrites
 * the forwarding headers, the real client IP can be used per request.
 */
@WebMvcTest
@ContextConfiguration(classes = SecurityTestContext.class)
@TestPropertySource(properties = {
        "chatbi.auth.jwt-secret=integration-test-secret-0123456789abcdef",
        "chatbi.auth.jwt-issuer=chatbi-test",
        "chatbi.auth.demo-header-enabled=false",
        "chatbi.rate-limit.enabled=true",
        "chatbi.rate-limit.login-requests-per-minute=1",
        "chatbi.rate-limit.trust-forwarded-headers=true"
})
class RateLimitTrustedProxyTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppUserMapper userMapper;

    @MockBean
    private ChatBiMetrics metrics;

    @Test
    void trustedProxyHeadersIdentifyDistinctClients() throws Exception {
        login("1.1.1.1").andExpect(status().isOk());
        login("2.2.2.2").andExpect(status().isOk());
        login("2.2.2.2").andExpect(status().isTooManyRequests());
    }

    private org.springframework.test.web.servlet.ResultActions login(String forwardedFor)
            throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .header("X-Forwarded-For", forwardedFor)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"password\":\"y\"}"));
    }
}
