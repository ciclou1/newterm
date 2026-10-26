package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import com.chatbi.domain.entity.AppUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Minimal HS256 JWT implementation. Keeping the format local avoids adding a
 * JWT library for a single algorithm; signature verification is constant-time
 * and the token is deliberately size-limited before parsing.
 */
@Service
public class JwtService {

    private static final String ALGORITHM = "HS256";
    private static final String MAC = "HmacSHA256";
    private static final int MIN_SECRET_BYTES = 32;
    private static final int MAX_TOKEN_CHARS = 8192;
    /** Fingerprint of the current password hash, checked on every request. */
    static final String CLAIM_PASSWORD_FINGERPRINT = "pwd";
    private static final int FINGERPRINT_BYTES = 8;

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final String issuer;
    private final long ttlSeconds;

    public JwtService(ChatBiProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ChatBiProperties.Auth auth = properties.getAuth();
        String configured = auth.getJwtSecret();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "CHATBI_JWT_SECRET is required (set a random value of at least 32 bytes)");
        }
        this.secret = configured.getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "CHATBI_JWT_SECRET is too short: HS256 requires at least " + MIN_SECRET_BYTES + " bytes");
        }
        this.issuer = auth.getJwtIssuer() == null || auth.getJwtIssuer().isBlank()
                ? "chatbi" : auth.getJwtIssuer();
        this.ttlSeconds = auth.getAccessTokenTtlSeconds() <= 0 ? 3600 : auth.getAccessTokenTtlSeconds();
    }

    public String issue(AppUser user) {
        return issue(user.getUsername(), user.getId(), user.getDisplayName(), UserRoles.of(user),
                user.getPasswordHash(), ttlSeconds);
    }

    public String issue(String username, Long userId, String displayName, List<String> roles,
                        String passwordHash, long ttl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(ttl);
        try {
            ObjectNode header = objectMapper.createObjectNode();
            header.put("alg", ALGORITHM);
            header.put("typ", "JWT");

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("iss", issuer);
            payload.put("sub", username);
            if (userId != null) {
                payload.put("uid", userId);
            }
            if (displayName != null) {
                payload.put("name", displayName);
            }
            ArrayNode roleArray = payload.putArray("roles");
            roles.forEach(roleArray::add);
            // Binds the token to the password that was current when it was
            // issued. Changing the password changes the hash, so every token
            // issued before the change stops validating without server state.
            payload.put(CLAIM_PASSWORD_FINGERPRINT, passwordFingerprint(passwordHash));
            payload.put("iat", now.getEpochSecond());
            payload.put("exp", expiresAt.getEpochSecond());

            String encodedHeader = encode(objectMapper.writeValueAsBytes(header));
            String encodedPayload = encode(objectMapper.writeValueAsBytes(payload));
            String signingInput = encodedHeader + "." + encodedPayload;
            return signingInput + "." + encode(sign(signingInput));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to issue JWT", e);
        }
    }

    public ChatBiPrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtValidationException("INVALID_TOKEN", "令牌为空");
        }
        if (token.length() > MAX_TOKEN_CHARS) {
            throw new JwtValidationException("INVALID_TOKEN", "令牌过长");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
            throw new JwtValidationException("INVALID_TOKEN", "令牌格式无效");
        }
        try {
            JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!ALGORITHM.equals(header.path("alg").asText())) {
                throw new JwtValidationException("INVALID_TOKEN", "不支持的签名算法");
            }

            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual;
            try {
                actual = Base64.getUrlDecoder().decode(parts[2]);
            } catch (IllegalArgumentException e) {
                throw new JwtValidationException("INVALID_TOKEN", "令牌签名无效");
            }
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new JwtValidationException("INVALID_TOKEN", "令牌签名无效");
            }

            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String tokenIssuer = payload.path("iss").asText("");
            if (!issuer.equals(tokenIssuer)) {
                throw new JwtValidationException("INVALID_TOKEN", "令牌签发方无效");
            }
            long now = Instant.now().getEpochSecond();
            long expiresAt = payload.path("exp").asLong(0);
            if (expiresAt <= 0) {
                throw new JwtValidationException("INVALID_TOKEN", "令牌缺少过期时间");
            }
            if (expiresAt <= now) {
                throw new JwtValidationException("TOKEN_EXPIRED", "登录已过期，请重新登录");
            }
            String username = payload.path("sub").asText("");
            if (username.isBlank()) {
                throw new JwtValidationException("INVALID_TOKEN", "令牌缺少用户主体");
            }
            List<String> roles = new ArrayList<>();
            JsonNode roleNode = payload.path("roles");
            if (roleNode.isArray()) {
                roleNode.forEach(role -> {
                    String value = role.asText("").trim().toUpperCase();
                    if (!value.isBlank()) {
                        roles.add(value);
                    }
                });
            }
            if (roles.isEmpty()) {
                roles.add(UserRoles.VIEWER);
            }
            Long userId = payload.has("uid") ? payload.path("uid").asLong() : null;
            String displayName = payload.path("name").asText(null);
            String passwordFingerprint = payload.path(CLAIM_PASSWORD_FINGERPRINT).asText(null);
            return new ChatBiPrincipal(username, userId, displayName, roles, passwordFingerprint);
        } catch (JwtValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new JwtValidationException("INVALID_TOKEN", "令牌无法解析");
        }
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * Stable, non-reversible fingerprint of a BCrypt hash. Only the first
     * {@value #FINGERPRINT_BYTES} bytes are used; the token reveals nothing
     * about the password or the stored hash beyond equality.
     */
    public static String passwordFingerprint(String passwordHash) {
        String material = passwordHash == null ? "" : passwordHash;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, FINGERPRINT_BYTES);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute password fingerprint", e);
        }
    }

    /** Constant-time comparison of a token claim with the current DB hash. */
    public static boolean passwordFingerprintMatches(String claim, String currentPasswordHash) {
        if (claim == null || claim.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(claim.getBytes(StandardCharsets.UTF_8),
                passwordFingerprint(currentPasswordHash).getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(MAC);
            mac.init(new SecretKeySpec(secret, MAC));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
