package com.chatbi.security;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.mapper.AppUserMapper;
import com.chatbi.metrics.ChatBiMetrics;
import com.chatbi.service.UserDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@ContextConfiguration(classes = SecurityTestContext.class)
@TestPropertySource(properties = {
        "chatbi.auth.jwt-secret=integration-test-secret-0123456789abcdef",
        "chatbi.auth.jwt-issuer=chatbi-test",
        "chatbi.auth.demo-header-enabled=false",
        "chatbi.rate-limit.enabled=true",
        "chatbi.rate-limit.requests-per-minute=1",
        "chatbi.rate-limit.burst-capacity=1",
        "chatbi.rate-limit.login-requests-per-minute=1"
})
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AppUserMapper userMapper;

    @MockBean
    private ChatBiMetrics metrics;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String viewerToken() {
        AppUser user = new AppUser();
        user.setId(10L);
        user.setUsername("zhangsan");
        user.setDisplayName("张三");
        user.setRoles("VIEWER");
        user.setEnabled(true);
        user.setIsAdmin(false);
        when(userMapper.selectOne(any())).thenReturn(user);
        return "Bearer " + jwtService.issue(user.getUsername(), user.getId(),
                user.getDisplayName(), UserRoles.of(user), 60);
    }

    @Test
    void chatEndpointReturns429AfterBurstIsExhausted() throws Exception {
        String authorization = viewerToken();

        mockMvc.perform(post("/api/chat/query").header("Authorization", authorization))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/chat/query").header("Authorization", authorization))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    void loginEndpointIsRateLimitedByClientIp() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"password\":\"y\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
