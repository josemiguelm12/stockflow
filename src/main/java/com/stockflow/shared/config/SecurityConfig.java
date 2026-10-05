package com.stockflow.shared.config;

import com.stockflow.identity.adapter.in.web.SessionAuthenticationFilter;
import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.shared.web.RateLimitFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.CorsFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.List;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({CorsProperties.class, RateLimitProperties.class})
public class SecurityConfig {

    private static final String UNAUTHORIZED_BODY =
            "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401}";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationEntryPoint entryPoint,
                                            AuthenticateSession authenticateSession,
                                            RateLimitProperties rateLimits, Clock clock) {
        http
                .cors(Customizer.withDefaults())
                // Tras CORS (el preflight no cuenta ni recibe 429 sin cabeceras CORS) y antes de autenticar.
                .addFilterAfter(new RateLimitFilter(rateLimits.globalPerMinute(), Map.of(
                        RateLimitFilter.LOGIN_PATH, rateLimits.loginPerMinute(),
                        RateLimitFilter.PASSWORD_FORGOT_PATH, rateLimits.passwordForgotPerMinute(),
                        RateLimitFilter.PASSWORD_RESET_PATH, rateLimits.passwordResetPerMinute(),
                        RateLimitFilter.PASSWORD_CHANGE_PATH, rateLimits.passwordChangePerMinute()), clock),
                        CorsFilter.class)
                .addFilterBefore(new SessionAuthenticationFilter(authenticateSession), AuthorizationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .headers(h -> h
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.NO_REFERRER))
                        .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/health", "/activate", "/reset-password").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/activate",
                                "/api/v1/auth/resend-activation",
                                "/api/v1/auth/login",
                                "/api/v1/auth/password/forgot",
                                "/api/v1/auth/password/reset").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout", "/api/v1/auth/password/change")
                        .authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(UNAUTHORIZED_BODY);
        };
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
