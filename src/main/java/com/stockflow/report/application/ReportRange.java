package com.stockflow.report.application;

import com.stockflow.identity.application.InvalidInputException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Intervalo UTC {@code [from, to)} de un reporte (RF-REP-05). Acepta instantes ISO-8601 con zona
 * ({@code 2026-01-01T00:00:00Z}) o fechas {@code yyyy-MM-dd} (medianoche UTC). Sin ambos límites: últimos 30 días hasta
 * ahora; con un solo límite el otro se deriva (hasta = ahora, o desde = hasta − 30 días). Máximo 366 días.
 */
public record ReportRange(Instant from, Instant to) {

    public static final Duration DEFAULT_SPAN = Duration.ofDays(30);
    public static final Duration MAX_SPAN = Duration.ofDays(366);

    public static ReportRange resolve(String desde, String hasta, Instant now) {
        Instant from = parse("desde", desde);
        Instant to = parse("hasta", hasta);
        if (to == null) {
            to = now;
        }
        if (from == null) {
            from = to.minus(DEFAULT_SPAN);
        }
        if (from.isAfter(to)) {
            throw new InvalidInputException("desde", "must not be after hasta");
        }
        if (Duration.between(from, to).compareTo(MAX_SPAN) > 0) {
            throw new InvalidInputException("hasta", "the range must not exceed 366 days");
        }
        return new ReportRange(from, to);
    }

    private static Instant parse(String field, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            if (text.length() == 10) {
                return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidInputException(field, "must be an ISO-8601 UTC date or instant");
        }
    }
}
