package com.stockflow.shared.config;

import com.stockflow.identity.adapter.out.security.JwtAccessTokenService;
import com.stockflow.identity.application.AccessTokenIssuer;
import com.stockflow.identity.application.AccessTokenVerifier;
import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.Login;
import com.stockflow.identity.application.Logout;
import com.stockflow.identity.application.PasswordHasher;
import com.stockflow.identity.application.SessionRepository;
import com.stockflow.identity.application.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Casos de uso de sesión y JWT: solo existen en la aplicación web. El worker SMTP no necesita
 * STOCKFLOW_JWT_SECRET, así que estos beans no se crean cuando arranca sin servidor.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(JwtProperties.class)
class SessionConfig {

    @Bean
    JwtAccessTokenService jwtAccessTokenService(JwtProperties properties, Clock clock) {
        return new JwtAccessTokenService(properties, clock);
    }

    @Bean
    Login login(UserRepository users, SessionRepository sessions, AccessTokenIssuer issuer,
                PasswordHasher hasher, Clock clock) {
        return new Login(users, sessions, issuer, hasher, clock);
    }

    @Bean
    Logout logout(SessionRepository sessions, Clock clock) {
        return new Logout(sessions, clock);
    }

    @Bean
    AuthenticateSession authenticateSession(AccessTokenVerifier verifier, SessionRepository sessions, Clock clock) {
        return new AuthenticateSession(verifier, sessions, clock);
    }
}
