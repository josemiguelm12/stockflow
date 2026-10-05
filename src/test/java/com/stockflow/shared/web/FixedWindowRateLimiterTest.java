package com.stockflow.shared.web;

import com.stockflow.support.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministas: el tiempo lo controla un reloj de prueba, no hay pausas reales. */
class FixedWindowRateLimiterTest {

    /** Inicio de minuto exacto (múltiplo de 60 s desde la época). */
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void allowsUpToTheLimitAndThenRejectsWithTheRemainingWindowAsRetryAfter() {
        var limiter = new FixedWindowRateLimiter(3, 100, clock);

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("1.1.1.1").allowed()).isTrue();
        }
        var rejected = limiter.tryAcquire("1.1.1.1");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(60);

        clock.advance(Duration.ofSeconds(20));
        assertThat(limiter.tryAcquire("1.1.1.1").retryAfterSeconds()).isEqualTo(40);
        clock.advance(Duration.ofMillis(39_500)); // 59,5 s en total: queda medio segundo, se redondea a 1
        assertThat(limiter.tryAcquire("1.1.1.1").retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void aNewWindowStartsFresh() {
        var limiter = new FixedWindowRateLimiter(1, 100, clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();

        clock.advance(Duration.ofSeconds(60));

        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        var limiter = new FixedWindowRateLimiter(1, 100, clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
        assertThat(limiter.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void stateStaysBounded() {
        var limiter = new FixedWindowRateLimiter(5, 3, clock);
        for (int i = 0; i < 50; i++) {
            limiter.tryAcquire("ip-" + i);
            assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(3);
        }

        clock.advance(Duration.ofSeconds(60));
        limiter.tryAcquire("fresh-1");
        limiter.tryAcquire("fresh-2");
        limiter.tryAcquire("fresh-3");
        limiter.tryAcquire("fresh-4");
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(3);
    }

    /** T02-H01: llenar el mapa con otras IP no devuelve el presupuesto a una IP que ya estaba limitada. */
    @Test
    void anAlreadyLimitedIpKeepsItsLimitWhenTheMapFillsUp() {
        var limiter = new FixedWindowRateLimiter(2, 3, clock);
        assertThat(limiter.tryAcquire("limited").allowed()).isTrue();
        assertThat(limiter.tryAcquire("limited").allowed()).isTrue();
        assertThat(limiter.tryAcquire("limited").allowed()).isFalse();

        // Otras IP llenan la capacidad (limited + 2 = 3) y siguen llegando más claves distintas.
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();
        assertThat(limiter.tryAcquire("c").allowed()).isTrue();
        for (int i = 0; i < 100; i++) {
            limiter.tryAcquire("flood-" + i);
        }

        var stillLimited = limiter.tryAcquire("limited");
        assertThat(stillLimited.allowed()).isFalse();
        assertThat(stillLimited.retryAfterSeconds()).isPositive();
        assertThat(limiter.trackedKeys()).isEqualTo(3);
    }

    @Test
    void whenFullNewKeysAreRejectedUntilTheWindowExpiresAndKnownKeysKeepWorking() {
        var limiter = new FixedWindowRateLimiter(5, 2, clock);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.tryAcquire("b").allowed()).isTrue();

        var rejected = limiter.tryAcquire("new");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isEqualTo(60);
        assertThat(limiter.tryAcquire("a").allowed()).isTrue();
        assertThat(limiter.trackedKeys()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(60));

        assertThat(limiter.tryAcquire("new").allowed()).isTrue();
        assertThat(limiter.trackedKeys()).isLessThanOrEqualTo(2);
    }

    /** T02-H01: la admisión es atómica, así que claves distintas y simultáneas nunca superan la capacidad. */
    @Test
    void concurrentDistinctKeysNeverExceedTheCapacity() throws Exception {
        int capacity = 50;
        var limiter = new FixedWindowRateLimiter(10, capacity, clock);
        AtomicInteger admitted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int t = 0; t < 8; t++) {
                int thread = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        if (limiter.tryAcquire("ip-" + thread + "-" + i).allowed()) {
                            admitted.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(admitted.get()).isEqualTo(capacity);
        assertThat(limiter.trackedKeys()).isEqualTo(capacity);
    }

    @Test
    void concurrentCallersNeverExceedTheLimit() throws Exception {
        var limiter = new FixedWindowRateLimiter(100, 100, clock);
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int t = 0; t < 8; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        if (limiter.tryAcquire("shared").allowed()) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get()).isEqualTo(100);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter(0, 10, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowRateLimiter(1, 0, clock)).isInstanceOf(IllegalArgumentException.class);
    }
}
