package com.chatbi.security;

import com.chatbi.config.ChatBiProperties;
import com.chatbi.metrics.ChatBiMetrics;
import com.chatbi.service.UserDirectory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableConfigurationProperties(ChatBiProperties.class)
public class SecurityConfig {

    @Bean
    public ServiceTokenAuthenticationFilter serviceTokenAuthenticationFilter(
            ChatBiProperties properties, UserDirectory userDirectory,
            SecurityErrorWriter writer, ChatBiMetrics chatBiMetrics) {
        return new ServiceTokenAuthenticationFilter(
                properties.getAuth().getServiceToken(),
                properties.getAuth().getServiceUsername(),
                userDirectory, writer, chatBiMetrics);
    }

    @Bean
    public RateLimitFilter rateLimitFilter(TokenBucketRateLimiter limiter,
                                           ChatBiProperties properties,
                                           ChatBiMetrics chatBiMetrics,
                                           SecurityErrorWriter writer) {
        return new RateLimitFilter(limiter, properties, chatBiMetrics, writer);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            ServiceTokenAuthenticationFilter serviceTokenAuthenticationFilter,
            RateLimitFilter rateLimitFilter,
            ObjectProvider<DemoHeaderAuthenticationFilter> demoHeaderFilter,
            SecurityErrorWriter errorWriter,
            ChatBiMetrics metrics) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, ex) -> {
                            metrics.authenticationFailure("anonymous");
                            errorWriter.write(request, response, 401,
                                    "AUTHENTICATION_REQUIRED", "请先登录");
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            metrics.authorizationDenied();
                            errorWriter.write(request, response, 403,
                                    "FORBIDDEN", "没有权限执行该操作");
                        }))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/auth/login", "/error",
                                "/actuator/health", "/actuator/health/**",
                                "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/semantic/permissions/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/semantic/**").hasRole("ADMIN")
                        .requestMatchers("/api/eval/**").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(serviceTokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, ServiceTokenAuthenticationFilter.class);

        DemoHeaderAuthenticationFilter demo = demoHeaderFilter.getIfAvailable();
        if (demo != null) {
            http.addFilterAfter(demo, JwtAuthenticationFilter.class);
        }
        http.addFilterBefore(rateLimitFilter, AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(ChatBiProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getSecurity().getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        source.registerCorsConfiguration("/actuator/**", configuration);
        return source;
    }
}
