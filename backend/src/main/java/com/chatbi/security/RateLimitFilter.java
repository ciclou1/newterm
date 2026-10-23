package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import com.chatbi.metrics.ChatBiMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Limits the expensive NL2SQL endpoint (and password login) per authenticated
 * user, falling back to the client IP for anonymous requests.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String CHAT_PATH = "/api/chat/query";
    private static final String LOGIN_PATH = "/api/auth/login";

    private final TokenBucketRateLimiter limiter;
    private final ChatBiProperties properties;
    private final ChatBiMetrics metrics;
    private final SecurityErrorWriter errorWriter;

    public RateLimitFilter(TokenBucketRateLimiter limiter, ChatBiProperties properties,
                           ChatBiMetrics metrics, SecurityErrorWriter errorWriter) {
        this.limiter = limiter;
        this.properties = properties;
        this.metrics = metrics;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!HttpMethod.POST.matches(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        String scope = null;
        int requestsPerMinute = 0;
        if (CHAT_PATH.equals(path)) {
            scope = "chat";
            requestsPerMinute = properties.getRateLimit().getRequestsPerMinute();
        } else if (LOGIN_PATH.equals(path)) {
            scope = "login";
            requestsPerMinute = properties.getRateLimit().getLoginRequestsPerMinute();
        }
        if (scope == null || !properties.getRateLimit().isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        String subject = subject(request);
        int burst = "chat".equals(scope) ? properties.getRateLimit().getBurstCapacity() : 0;
        if (limiter.tryAcquire(scope, subject, requestsPerMinute, burst)) {
            chain.doFilter(request, response);
            return;
        }

        long retryAfter = limiter.retryAfterSeconds(scope, subject, requestsPerMinute, burst);
        metrics.rateLimited(scope);
        response.setHeader("Retry-After", String.valueOf(Math.max(1L, retryAfter)));
        errorWriter.write(request, response, 429,
                "RATE_LIMITED", "请求过于频繁，请稍后再试");
    }

    private String subject(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ChatBiPrincipal principal) {
            return "user:" + principal.username();
        }
        return "ip:" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isBlank()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}
