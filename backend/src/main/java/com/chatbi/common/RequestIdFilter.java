package com.chatbi.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlates a request with the edge proxy, application logs and audit rows.
 * A client-supplied id is only accepted when it is short and safe (no CR/LF or
 * other log-injection characters); anything else is replaced. The resolved id
 * is echoed back so callers can quote it in incident reports.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID = "requestId";
    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && SAFE.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString().substring(0, 12);
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID);
        }
    }
}
