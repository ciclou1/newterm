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
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String AUTHORIZATION = "Authorization";
    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final UserDirectory userDirectory;
    private final SecurityErrorWriter errorWriter;
    private final ChatBiMetrics metrics;

    public JwtAuthenticationFilter(JwtService jwtService, UserDirectory userDirectory,
                                   SecurityErrorWriter errorWriter, ChatBiMetrics metrics) {
        this.jwtService = jwtService;
        this.userDirectory = userDirectory;
        this.errorWriter = errorWriter;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER.length()).trim();
        if (token.isEmpty()) {
            reject(request, response, "INVALID_TOKEN", "缺少 Bearer 令牌");
            return;
        }
        ChatBiPrincipal principal;
        try {
            principal = jwtService.parse(token);
        } catch (JwtValidationException e) {
            reject(request, response, e.getCode(), e.getMessage());
            return;
        }

        AppUser user = userDirectory.findByUsername(principal.username());
        if (user == null) {
            reject(request, response, "INVALID_TOKEN", "令牌中的用户不存在");
            return;
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            metrics.authenticationFailure("user_disabled");
            errorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "USER_DISABLED", "账号已禁用，请联系管理员");
            return;
        }

        List<String> roles = UserRoles.of(user);
        ChatBiPrincipal authenticated = new ChatBiPrincipal(user.getUsername(), user.getId(),
                user.getDisplayName(), roles);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(authenticated, token,
                        UserRoles.authorities(roles));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response,
                        String code, String message) throws IOException {
        metrics.authenticationFailure("jwt");
        errorWriter.write(request, response, HttpServletResponse.SC_UNAUTHORIZED, code, message);
    }
}
