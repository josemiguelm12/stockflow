package com.stockflow.shared.web;

import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limitador de ventana fija de un minuto por clave (IP). Estado local en memoria: solo vale para una instancia;
 * con varias instancias haría falta un almacén distribuido (fuera de alcance). El estado está acotado: al superar
 * {@code maxKeys} se descartan las ventanas vencidas y, si aun así no basta, se reinicia el mapa (se prefiere
 * perder precisión a crecer sin límite). Seguro ante concurrencia: cada actualización es atómica por clave.
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
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, int maxKeys, Clock clock) {
        if (limit <= 0 || maxKeys <= 0) {
            throw new IllegalArgumentException("limit and maxKeys must be positive");
        }
        this.limit = limit;
        this.maxKeys = maxKeys;
        this.clock = clock;
    }

    public Decision tryAcquire(String key) {
        long now = clock.millis();
        long windowStart = now - Math.floorMod(now, WINDOW_MILLIS);
        Window window = windows.compute(key, (k, current) ->
                current == null || current.start() != windowStart
                        ? new Window(windowStart, 1)
                        : new Window(windowStart, current.count() + 1));

        if (windows.size() > maxKeys) {
            windows.values().removeIf(w -> w.start() != windowStart);
            if (windows.size() > maxKeys) {
                windows.clear();
            }
        }

        if (window.count() <= limit) {
            return new Decision(true, 0);
        }
        long remainingMillis = windowStart + WINDOW_MILLIS - now;
        return new Decision(false, Math.max(1, (remainingMillis + 999) / 1000));
    }

    int trackedKeys() {
        return windows.size();
    }
}
