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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The legacy demo identity header is opt-in. This context enables it and
 * verifies it resolves real users (and enforces their roles) rather than
 * trusting an arbitrary username.
 */
@WebMvcTest
@ContextConfiguration(classes = SecurityTestContext.class)
@TestPropertySource(properties = {
        "chatbi.auth.jwt-secret=integration-test-secret-0123456789abcdef",
        "chatbi.auth.jwt-issuer=chatbi-test",
        "chatbi.auth.demo-header-enabled=true",
        "chatbi.rate-limit.enabled=false"
})
class DemoHeaderSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AppUserMapper userMapper;

    @MockBean
    private ChatBiMetrics metrics;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AppUser user(String username, String roles, boolean admin) {
        AppUser user = new AppUser();
        user.setId(10L);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setRoles(roles);
        user.setEnabled(true);
        user.setIsAdmin(admin);
        return user;
    }

    @Test
    void demoHeaderResolvesAdminWhenExplicitlyEnabled() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(user("lisi", "ADMIN", true));

        mockMvc.perform(get("/api/users/probe").header("X-ChatBI-User", "lisi"))
                .andExpect(status().isOk());
    }

    @Test
    void demoHeaderStillEnforcesRole() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(user("zhangsan", "VIEWER", false));

        mockMvc.perform(get("/api/conversations/probe").header("X-ChatBI-User", "zhangsan"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/probe").header("X-ChatBI-User", "zhangsan"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownDemoUserIsRejected() throws Exception {
        when(userMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(get("/api/conversations/probe").header("X-ChatBI-User", "ghost"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_DEMO_USER"));
    }

    @Test
    void missingHeaderIsStillAnonymous() throws Exception {
        mockMvc.perform(get("/api/conversations/probe"))
                .andExpect(status().isUnauthorized());
    }
}
