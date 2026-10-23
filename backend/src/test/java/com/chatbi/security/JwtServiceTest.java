package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef0123456789";

    private JwtService service(String secret, String issuer) {
        ChatBiProperties properties = new ChatBiProperties();
        properties.getAuth().setJwtSecret(secret);
        properties.getAuth().setJwtIssuer(issuer);
        properties.getAuth().setAccessTokenTtlSeconds(3600);
        return new JwtService(properties, new ObjectMapper());
    }

    private JwtService service() {
        return service(SECRET, "chatbi-test");
    }

    @Test
    void roundTripsPrincipal() {
        JwtService jwt = service();
        String token = jwt.issue("alice", 7L, "Alice", List.of("ADMIN", "VIEWER"), 60);

        ChatBiPrincipal principal = jwt.parse(token);

        assertThat(principal.username()).isEqualTo("alice");
        assertThat(principal.userId()).isEqualTo(7L);
        assertThat(principal.displayName()).isEqualTo("Alice");
        assertThat(principal.roles()).containsExactly("ADMIN", "VIEWER");
        assertThat(principal.getName()).isEqualTo("alice");
    }

    @Test
    void defaultsEmptyRolesToViewer() {
        JwtService jwt = service();
        String token = jwt.issue("bob", 8L, "Bob", List.of(), 60);

        assertThat(jwt.parse(token).roles()).containsExactly("VIEWER");
    }

    @Test
    void rejectsExpiredToken() {
        JwtService jwt = service();
        String token = jwt.issue("alice", 7L, "Alice", List.of("VIEWER"), -5);

        assertThatThrownBy(() -> jwt.parse(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getCode())
                .isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    void rejectsTamperedSignature() {
        JwtService jwt = service();
        String token = jwt.issue("alice", 7L, "Alice", List.of("VIEWER"), 60);
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'a' ? 'b' : 'a');

        assertThatThrownBy(() -> jwt.parse(tampered))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getCode())
                .isEqualTo("INVALID_TOKEN");
    }

    @Test
    void rejectsTamperedPayload() {
        JwtService jwt = service();
        String token = jwt.issue("alice", 7L, "Alice", List.of("VIEWER"), 60);
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"chatbi-test\",\"sub\":\"admin\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwt.parse(forged))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsWrongIssuer() {
        String token = service(SECRET, "issuer-a").issue("alice", 7L, "Alice", List.of("VIEWER"), 60);

        assertThatThrownBy(() -> service(SECRET, "issuer-b").parse(token))
                .isInstanceOf(JwtValidationException.class)
                .extracting(e -> ((JwtValidationException) e).getCode())
                .isEqualTo("INVALID_TOKEN");
    }

    @Test
    void rejectsAlgNoneHeader() {
        JwtService jwt = service();
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"chatbi-test\",\"sub\":\"admin\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> jwt.parse(header + "." + payload + "."))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void rejectsMalformedAndOversizedTokens() {
        JwtService jwt = service();
        assertThatThrownBy(() -> jwt.parse("not-a-token")).isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> jwt.parse("x".repeat(9000))).isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(() -> jwt.parse("")).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void failsFastOnMissingOrShortSecret() {
        assertThatThrownBy(() -> service("", "chatbi-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHATBI_JWT_SECRET");
        assertThatThrownBy(() -> service("too-short", "chatbi-test"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }
}
