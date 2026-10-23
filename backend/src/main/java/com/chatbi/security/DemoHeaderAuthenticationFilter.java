package com.chatbi.security;

import com.chatbi.domain.entity.AppUser;
import com.chatbi.metrics.ChatBiMetrics;
import com.chatbi.service.UserDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Legacy demo identity. This filter only exists when
 * {@code chatbi.auth.demo-header-enabled=true}; otherwise X-ChatBI-User has no
 * effect at all and cannot be used to impersonate an administrator.
 */
@Component
@ConditionalOnProperty(name = "chatbi.auth.demo-header-enabled", havingValue = "true")
public class DemoHeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-ChatBI-User";

    private final UserDirectory userDirectory;
    private final SecurityErrorWriter errorWriter;
    private final ChatBiMetrics metrics;

    public DemoHeaderAuthenticationFilter(UserDirectory userDirectory,
                                          SecurityErrorWriter errorWriter, ChatBiMetrics metrics) {
        this.userDirectory = userDirectory;
        this.errorWriter = errorWriter;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String username = request.getHeader(HEADER);
        if (username == null || username.isBlank()
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }
        AppUser user = userDirectory.findByUsername(username.trim());
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            metrics.authenticationFailure("demo_header_invalid_user");
            errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                    "INVALID_DEMO_USER", "演示身份不存在或已禁用");
            return;
        }
        List<String> roles = UserRoles.of(user);
        ChatBiPrincipal principal = new ChatBiPrincipal(user.getUsername(), user.getId(),
                user.getDisplayName(), roles);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null,
                        UserRoles.authorities(roles));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }
}
