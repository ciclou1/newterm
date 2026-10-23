package com.chatbi.security;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.metrics.ChatBiMetrics;
import com.chatbi.service.UserDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Shared-secret identity for the stdio MCP server. The resulting principal is
 * deliberately stripped of ADMIN even if the referenced account is
 * misconfigured; service access is read-only and limited to the chat query
 * endpoint.
 */
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-ChatBI-Service-Token";
    private static final String ALLOWED_PATH_PREFIX = "/api/chat/";

    private final String configuredToken;
    private final String serviceUsername;
    private final UserDirectory userDirectory;
    private final SecurityErrorWriter errorWriter;
    private final ChatBiMetrics metrics;

    public ServiceTokenAuthenticationFilter(String configuredToken, String serviceUsername,
                                            UserDirectory userDirectory,
                                            SecurityErrorWriter errorWriter, ChatBiMetrics metrics) {
        this.configuredToken = configuredToken;
        this.serviceUsername = serviceUsername;
        this.userDirectory = userDirectory;
        this.errorWriter = errorWriter;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented == null || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(ALLOWED_PATH_PREFIX)) {
            metrics.authenticationFailure("service_token_scope");
            errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "SERVICE_SCOPE_FORBIDDEN",
                    "服务令牌仅可用于问数接口");
            return;
        }
        if (configuredToken == null || configuredToken.isBlank()) {
            metrics.authenticationFailure("service_token_not_configured");
            errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "SERVICE_TOKEN_NOT_CONFIGURED",
                    "后端未配置 CHATBI_MCP_SERVICE_TOKEN，服务身份不可用");
            return;
        }
        if (!constantTimeEquals(configuredToken, presented)) {
            metrics.authenticationFailure("service_token_invalid");
            errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "INVALID_SERVICE_TOKEN", "服务令牌无效");
            return;
        }

        AppUser user = userDirectory.findByUsername(serviceUsername);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            metrics.authenticationFailure("service_user_unavailable");
            errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "SERVICE_PRINCIPAL_UNAVAILABLE", "服务账号不存在或已禁用");
            return;
        }
        List<String> roles = UserRoles.of(user).stream()
                .filter(role -> !UserRoles.ADMIN.equals(role))
                .toList();
        if (roles.isEmpty()) {
            roles = List.of(UserRoles.VIEWER);
        }
        ChatBiPrincipal principal = new ChatBiPrincipal(user.getUsername(), user.getId(),
                user.getDisplayName(), roles);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null,
                        UserRoles.authorities(roles));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
