package com.stockflow.shared.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** pgjdbc no admite Instant como parámetro; los instantes se guardan como timestamptz en UTC. */
public final class Timestamps {

    private Timestamps() {
    }

    public static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
