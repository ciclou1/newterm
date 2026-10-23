package com.chatbi.semantic;

import com.chatbi.config.ChatBiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

@Slf4j
@Component
public class SchemaLinkCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public SchemaLinkCache(StringRedisTemplate redis,
                           ObjectMapper objectMapper,
                           ChatBiProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(properties.getSchemaLink().getCacheTtlSeconds());
    }

    public Optional<com.chatbi.linking.SchemaContext> get(String question, long version) {
        try {
            String value = redis.opsForValue().get(key(question, version));
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, com.chatbi.linking.SchemaContext.class));
        } catch (Exception e) {
            log.debug("Schema link cache read skipped: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public void put(String question, long version, com.chatbi.linking.SchemaContext context) {
        try {
            redis.opsForValue().set(key(question, version), objectMapper.writeValueAsString(context), ttl);
        } catch (Exception e) {
            log.debug("Schema link cache write skipped: {}", e.getMessage());
        }
    }

    private String key(String question, long version) {
        return "chatbi:schema-link:" + version + ":" + sha256(question.toLowerCase().trim());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
