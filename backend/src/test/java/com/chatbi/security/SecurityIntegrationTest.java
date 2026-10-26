package com.chatbi.security;

import com.chatbi.mapper.AppUserMapper;
import com.chatbi.metrics.ChatBiMetrics;
import com.chatbi.domain.entity.AppUser;
import com.chatbi.service.UserDirectory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@ContextConfiguration(classes = SecurityTestContext.class)
@TestPropertySource(properties = {
        "chatbi.auth.jwt-secret=integration-test-secret-0123456789abcdef",
        "chatbi.auth.jwt-issuer=chatbi-test",
        "chatbi.auth.demo-header-enabled=false",
        "chatbi.auth.service-token=service-token-123",
        "chatbi.auth.service-username=mcp-service",
        "chatbi.rate-limit.enabled=false"
})
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockBean
    private AppUserMapper userMapper;

    @MockBean
    private ChatBiMetrics metrics;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private AppUser user(String username, String roles, boolean enabled, boolean admin) {
        AppUser user = new AppUser();
        user.setId(10L);
        user.setUsername(username);
        user.setDisplayName(username);
        user.setRoles(roles);
        user.setEnabled(enabled);
        user.setIsAdmin(admin);
        user.setPasswordHash("$2a$12$hash-for-" + username);
        return user;
    }

    private void useUser(AppUser user) {
        when(userMapper.selectOne(any())).thenReturn(user);
    }

    private String token(AppUser user, long ttlSeconds) {
        return jwtService.issue(user.getUsername(), user.getId(), user.getDisplayName(),
                UserRoles.of(user), user.getPasswordHash(), ttlSeconds);
    }

    private String bearer(AppUser user) {
        return "Bearer " + token(user, 60);
    }

    /**
     * Reproduces a token signed by a previous release that did not bind the
     * password hash; it must not be accepted after this upgrade.
     */
    private String legacyTokenWithoutFingerprint(AppUser user) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("iss", "chatbi-test");
        payload.put("sub", user.getUsername());
        payload.put("uid", user.getId());
        payload.put("name", user.getDisplayName());
        ArrayNode roles = payload.putArray("roles");
        roles.add("VIEWER");
        long now = Instant.now().getEpochSecond();
        payload.put("iat", now);
        payload.put("exp", now + 60);

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String encodedHeader = encoder.encodeToString(mapper.writeValueAsBytes(header));
        String encodedPayload = encoder.encodeToString(mapper.writeValueAsBytes(payload));
        String signingInput = encodedHeader + "." + encodedPayload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("integration-test-secret-0123456789abcdef"
                .getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = encoder.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        return signingInput + "." + signature;
    }

    @Test
    void anonymousRequestToBusinessApiIsRejected() throws Exception {
        mockMvc.perform(get("/api/conversations/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void forgedDemoHeaderIsIgnoredByDefault() throws Exception {
        mockMvc.perform(get("/api/conversations/probe").header("X-ChatBI-User", "lisi"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/users/probe").header("X-ChatBI-User", "lisi"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginEndpointIsPublic() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"lisi\",\"password\":\"x\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void viewerCanReachBusinessApisButNotAdminApis() throws Exception {
        AppUser viewer = user("zhangsan", "VIEWER", true, false);
        useUser(viewer);

        mockMvc.perform(get("/api/conversations/probe").header("Authorization", bearer(viewer)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/users/probe").header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/semantic/permissions/probe")
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/semantic/probe").header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/semantic/probe").header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/semantic/probe").header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/eval/probe").header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReachAdminApis() throws Exception {
        AppUser admin = user("lisi", "ADMIN", true, true);
        useUser(admin);
        String authorization = bearer(admin);

        mockMvc.perform(get("/api/users/probe").header("Authorization", authorization))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/semantic/permissions/probe")
                        .header("Authorization", authorization))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/semantic/probe").header("Authorization", authorization))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/semantic/probe").header("Authorization", authorization))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/semantic/probe").header("Authorization", authorization))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/eval/probe").header("Authorization", authorization))
                .andExpect(status().isOk());
    }

    @Test
    void signedTokenRolesDoNotOverrideDatabaseRoles() throws Exception {
        AppUser viewer = user("zhangsan", "VIEWER", true, false);
        useUser(viewer);
        String escalated = jwtService.issue(viewer.getUsername(), viewer.getId(),
                viewer.getDisplayName(), List.of("ADMIN"), viewer.getPasswordHash(), 60);

        mockMvc.perform(get("/api/users/probe").header("Authorization", "Bearer " + escalated))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        AppUser viewer = user("zhangsan", "VIEWER", true, false);
        useUser(viewer);

        mockMvc.perform(get("/api/conversations/probe")
                        .header("Authorization", "Bearer " + token(viewer, -5)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        AppUser viewer = user("zhangsan", "VIEWER", true, false);
        useUser(viewer);
        String token = token(viewer, 60);
        // Flip the first signature character: the last Base64url character can
        // differ only in padding bits and decode to the same signature.
        int signatureStart = token.lastIndexOf('.') + 1;
        char first = token.charAt(signatureStart);
        String tampered = token.substring(0, signatureStart)
                + (first == 'a' ? 'b' : 'a')
                + token.substring(signatureStart + 1);

        mockMvc.perform(get("/api/conversations/probe")
                        .header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void tokenForDeletedUserIsRejected() throws Exception {
        AppUser viewer = user("zhangsan", "VIEWER", true, false);
        String token = token(viewer, 60);
        when(userMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(get("/api/conversations/probe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserTokenIsRejected() throws Exception {
        AppUser disabled = user("zhangsan", "VIEWER", false, false);
        String token = token(disabled, 60);
        useUser(disabled);

        mockMvc.perform(get("/api/conversations/probe")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("USER_DISABLED"));
    }

    @Test
    void tokenIssuedBeforeAPasswordChangeIsRejected() throws Exception {
        AppUser before = user("zhangsan", "VIEWER", true, false);
        before.setPasswordHash("$2a$12$old-password-hash");
        String issued = token(before, 60);

        AppUser after = user("zhangsan", "VIEWER", true, false);
        after.setPasswordHash("$2a$12$new-password-hash");
        useUser(after);

        mockMvc.perform(get("/api/conversations/probe").header("Authorization", "Bearer " + issued))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    void tokenIssuedAfterAPasswordChangeIsAccepted() throws Exception {
        AppUser after = user("zhangsan", "VIEWER", true, false);
        after.setPasswordHash("$2a$12$new-password-hash");
        useUser(after);

        mockMvc.perform(get("/api/conversations/probe")
                        .header("Authorization", "Bearer " + token(after, 60)))
                .andExpect(status().isOk());
    }

    @Test
    void tokenWithoutPasswordFingerprintIsRejected() throws Exception {
        AppUser viewer = user("zhangsan", "VIEWER", true, false);
        useUser(viewer);

        mockMvc.perform(get("/api/conversations/probe")
                        .header("Authorization", "Bearer " + legacyTokenWithoutFingerprint(viewer)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
    }

    @Test
    void serviceTokenIsLimitedToChatQueries() throws Exception {
        AppUser service = user("mcp-service", "ADMIN", true, true);
        useUser(service);

        mockMvc.perform(post("/api/chat/query")
                        .header("X-ChatBI-Service-Token", "service-token-123"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/conversations/probe")
                        .header("X-ChatBI-Service-Token", "service-token-123"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SERVICE_SCOPE_FORBIDDEN"));

        mockMvc.perform(get("/api/users/probe")
                        .header("X-ChatBI-Service-Token", "service-token-123"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SERVICE_SCOPE_FORBIDDEN"));
    }

    @Test
    void invalidServiceTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/chat/query")
                        .header("X-ChatBI-Service-Token", "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SERVICE_TOKEN"));
    }

    @Test
    void actuatorMetricsRequireAdmin() throws Exception {
        AppUser viewer = user("zhangsan", "VIEWER", true, false);
        useUser(viewer);

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", bearer(viewer)))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthDetailsEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/health/ai"))
                .andExpect(status().isUnauthorized());
    }
}
