package com.stockflow.report.application;

import com.stockflow.identity.application.InvalidInputException;

import java.util.Locale;

/** Tamaño del período de agrupación (UTC). Semana ISO (lunes). Por defecto DAY. */
public enum ReportGranularity {
    DAY("day"), WEEK("week"), MONTH("month");

    private final String unit;

    ReportGranularity(String unit) {
        this.unit = unit;
    }

    /** Argumento de {@code date_trunc}; siempre un literal de esta lista cerrada, nunca texto del cliente. */
    public String unit() {
        return unit;
    }

    public static ReportGranularity parse(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidInputException("granularity", "must be one of DAY, WEEK, MONTH");
        }
    }
}
