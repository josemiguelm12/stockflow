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
import java.util.HashMap;
import java.util.Map;

/**
 * Rate limiting por IP de la conexión (request.getRemoteAddr()); X-Forwarded-For se ignora a propósito porque
 * cualquiera puede falsificarlo. Aplica a /api/v1/**: un límite global y, además, un límite propio e independiente
 * para cada ruta POST sensible (login, forgot, reset y change). Es independiente del bloqueo por cuenta.
 * /api/health y el resto de rutas quedan fuera.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    public static final String LOGIN_PATH = "/api/v1/auth/login";
    public static final String PASSWORD_FORGOT_PATH = "/api/v1/auth/password/forgot";
    public static final String PASSWORD_RESET_PATH = "/api/v1/auth/password/reset";
    public static final String PASSWORD_CHANGE_PATH = "/api/v1/auth/password/change";

    private static final String API_PREFIX = "/api/v1/";
    private static final int MAX_TRACKED_IPS = 50_000;
    private static final String BODY = "{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429}";

    private final FixedWindowRateLimiter global;
    /** Un limitador por ruta POST; las rutas no incluidas solo cuentan para el límite global. */
    private final Map<String, FixedWindowRateLimiter> postRoutes = new HashMap<>();

    public RateLimitFilter(int globalPerMinute, Map<String, Integer> postRouteLimits, Clock clock) {
        this.global = new FixedWindowRateLimiter(globalPerMinute, MAX_TRACKED_IPS, clock);
        postRouteLimits.forEach((path, limit) ->
                postRoutes.put(path, new FixedWindowRateLimiter(limit, MAX_TRACKED_IPS, clock)));
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
        FixedWindowRateLimiter routeLimiter = "POST".equals(request.getMethod())
                ? postRoutes.get(request.getRequestURI()) : null;
        var routeDecision = routeLimiter == null ? null : routeLimiter.tryAcquire(ip);

        boolean blocked = !globalDecision.allowed() || (routeDecision != null && !routeDecision.allowed());
        if (!blocked) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(
                globalDecision.allowed() ? 0 : globalDecision.retryAfterSeconds(),
                routeDecision == null || routeDecision.allowed() ? 0 : routeDecision.retryAfterSeconds());
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}
