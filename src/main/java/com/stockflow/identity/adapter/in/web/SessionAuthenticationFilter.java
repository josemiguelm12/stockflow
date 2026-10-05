package com.stockflow.identity.adapter.in.web;

import com.stockflow.identity.application.AuthenticateSession;
import com.stockflow.identity.application.AuthenticatedUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Autentica solo con la cabecera Authorization: Bearer. No acepta el token en query string ni en cookies.
 * Si el token no es válido no autentica y deja continuar: la petición a un recurso protegido termina en 401
 * por el AuthenticationEntryPoint, y las rutas públicas siguen funcionando.
 */
public class SessionAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";
    private static final int MAX_TOKEN_LENGTH = 4096;

    private final AuthenticateSession authenticateSession;

    public SessionAuthenticationFilter(AuthenticateSession authenticateSession) {
        this.authenticateSession = authenticateSession;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            if (!token.isEmpty() && token.length() <= MAX_TOKEN_LENGTH) {
                authenticateSession.authenticate(token).ifPresent(SessionAuthenticationFilter::establish);
            }
        }
        chain.doFilter(request, response);
    }

    private static void establish(AuthenticatedUser user) {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
