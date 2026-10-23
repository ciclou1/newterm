package com.chatbi.security;

import com.chatbi.service.UserDirectory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Minimal context for security slice tests. The application class carries
 * {@code @MapperScan}, which would force MyBatis (and a database) into a
 * context whose controllers and mappers are all stubbed. Importing only the
 * security beans keeps the test focused and database-free.
 */
@Configuration
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SecurityErrorWriter.class,
        UserDirectory.class, JwtService.class, TokenBucketRateLimiter.class,
        DemoHeaderAuthenticationFilter.class, SecurityProbeController.class})
public class SecurityTestContext {
}
