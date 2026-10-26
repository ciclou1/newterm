package com.chatbi.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exposes dependency reachability as gauges so alerting does not depend on
 * exporters or on parsing health JSON:
 * {@code chatbi_dependency_up{dependency="postgres"|"redis"}} is 1 when the
 * last probe succeeded and 0 otherwise. Probe failures are logged at WARN and
 * never propagate, so a dependency outage does not crash the scheduler.
 */
@Slf4j
@Component
public class DependencyHealthMetrics {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final AtomicInteger postgresUp = new AtomicInteger(0);
    private final AtomicInteger redisUp = new AtomicInteger(0);

    public DependencyHealthMetrics(JdbcTemplate jdbcTemplate, StringRedisTemplate redis,
                                   MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        Gauge.builder("chatbi.dependency.up", postgresUp, AtomicInteger::get)
                .tags(Tags.of("dependency", "postgres"))
                .description("1 when the last dependency probe succeeded")
                .register(registry);
        Gauge.builder("chatbi.dependency.up", redisUp, AtomicInteger::get)
                .tags(Tags.of("dependency", "redis"))
                .description("1 when the last dependency probe succeeded")
                .register(registry);
    }

    @Scheduled(initialDelayString = "${chatbi.metrics.dependency-probe-initial-delay-ms:5000}",
            fixedDelayString = "${chatbi.metrics.dependency-probe-ms:15000}")
    public void probe() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            postgresUp.set(1);
        } catch (Exception e) {
            postgresUp.set(0);
            log.warn("PostgreSQL probe failed: {}", e.getMessage());
        }
        try (RedisConnection connection = redis.getConnectionFactory().getConnection()) {
            connection.ping();
            redisUp.set(1);
        } catch (Exception e) {
            redisUp.set(0);
            log.warn("Redis probe failed: {}", e.getMessage());
        }
    }
}
