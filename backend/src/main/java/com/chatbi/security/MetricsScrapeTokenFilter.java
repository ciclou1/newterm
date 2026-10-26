package com.chatbi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Internal Prometheus scrape credential. It only ever authenticates the exact
 * {@code /actuator/prometheus} path and grants {@code ROLE_METRICS}, so a
 * leaked scrape token cannot read other actuator endpoints, query data, or
 * perform administration. When no token is configured the filter is a no-op
 * and the endpoint stays ADMIN-only.
 */
public class MetricsScrapeTokenFilter extends OncePerRequestFilter {

    public static final String PATH = "/actuator/prometheus";
    public static final String AUTHORIZATION = "Authorization";
    public static final String ROLE = "METRICS";
    private static final String BEARER = "Bearer ";

    private final String configuredToken;

    public MetricsScrapeTokenFilter(String configuredToken) {
        this.configuredToken = configuredToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (configuredToken == null || configuredToken.isBlank()
                || !PATH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }
        String presented = header.substring(BEARER.length()).trim();
        if (presented.isEmpty() || !constantTimeEquals(configuredToken, presented)) {
            // Leave the request anonymous: the authorization rules reject it.
            chain.doFilter(request, response);
            return;
        }
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken("metrics-scraper", null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + ROLE)));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        chain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
