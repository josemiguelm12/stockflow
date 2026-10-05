package com.stockflow.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;

/**
 * Rate limiting por IP de la conexión (request.getRemoteAddr()); X-Forwarded-For se ignora a propósito porque
 * cualquiera puede falsificarlo. Aplica a /api/v1/**: un límite global y otro adicional para POST /auth/login.
 * Es independiente del bloqueo por cuenta. /api/health y el resto de rutas quedan fuera.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String API_PREFIX = "/api/v1/";
    static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final int MAX_TRACKED_IPS = 50_000;
    private static final String BODY = "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429}";

    private final FixedWindowRateLimiter global;
    private final FixedWindowRateLimiter login;

    public RateLimitFilter(int globalPerMinute, int loginPerMinute, Clock clock) {
        this.global = new FixedWindowRateLimiter(globalPerMinute, MAX_TRACKED_IPS, clock);
        this.login = new FixedWindowRateLimiter(loginPerMinute, MAX_TRACKED_IPS, clock);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        var globalDecision = global.tryAcquire(ip);
        var loginDecision = isLogin(request) ? login.tryAcquire(ip) : null;

        boolean blocked = !globalDecision.allowed() || (loginDecision != null && !loginDecision.allowed());
        if (!blocked) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(
                globalDecision.allowed() ? 0 : globalDecision.retryAfterSeconds(),
                loginDecision == null || loginDecision.allowed() ? 0 : loginDecision.retryAfterSeconds());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }

    private static boolean isLogin(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && LOGIN_PATH.equals(request.getRequestURI());
    }
}
