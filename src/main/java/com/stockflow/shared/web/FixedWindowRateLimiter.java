package com.stockflow.shared.web;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Limitador de ventana fija de un minuto por clave (IP). Estado local en memoria: solo vale para una instancia;
 * con varias instancias haría falta un almacén distribuido (fuera de alcance).
 *
 * <p>El estado está estrictamente acotado a {@code maxKeys} claves y la admisión es atómica (toda la operación
 * ocurre bajo un mismo cerrojo), de modo que ni las inserciones simultáneas pueden superar la capacidad. Cuando
 * el mapa está lleno se descartan las ventanas ya vencidas; si aun así no hay espacio, las claves <em>nuevas</em>
 * se rechazan hasta que venza la ventana actual. Nunca se borran las ventanas vigentes: una IP ya limitada
 * conserva su límite aunque otras IP llenen el mapa. El coste es que, bajo una inundación de IPs distintas,
 * las IPs nuevas reciben 429 durante el resto de la ventana (se prefiere fallar cerrado a perder límites).
 */
public class FixedWindowRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    public record Decision(boolean allowed, long retryAfterSeconds) {
    }

    private record Window(long start, int count) {
    }

    private final int limit;
    private final int maxKeys;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    /** Ventana en la que ya se purgó: evita recorrer el mapa en cada petición rechazada por falta de espacio. */
    private long lastPurgedWindow = Long.MIN_VALUE;

    public FixedWindowRateLimiter(int limit, int maxKeys, Clock clock) {
        if (limit <= 0 || maxKeys <= 0) {
            throw new IllegalArgumentException("limit and maxKeys must be positive");
        }
        this.limit = limit;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    public synchronized Decision tryAcquire(String key) {
        long now = clock.millis();
        long windowStart = now - Math.floorMod(now, WINDOW_MILLIS);
        long retryAfter = Math.max(1, (windowStart + WINDOW_MILLIS - now + 999) / 1000);

        Window current = windows.get(key);
        if (current == null && windows.size() >= maxKeys) {
            purgeExpired(windowStart);
            if (windows.size() >= maxKeys) {
                return new Decision(false, retryAfter);
            }
        }

        int count = current != null && current.start() == windowStart ? current.count() + 1 : 1;
        windows.put(key, new Window(windowStart, count));
        return count <= limit ? new Decision(true, 0) : new Decision(false, retryAfter);
    }

    private void purgeExpired(long windowStart) {
        if (lastPurgedWindow == windowStart) {
            return;
        }
        windows.values().removeIf(w -> w.start() != windowStart);
        lastPurgedWindow = windowStart;
    }

    synchronized int trackedKeys() {
        return windows.size();
    }
}
