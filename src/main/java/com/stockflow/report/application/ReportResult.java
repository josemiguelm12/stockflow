package com.stockflow.report.application;

import java.time.Instant;
import java.util.List;

/** Resultado agregado. {@code scope}: GLOBAL (ADMIN) u OWN (solo datos propios). */
public record ReportResult<T>(Instant from, Instant to, ReportGranularity granularity, String scope, List<T> groups) {

    static final String GLOBAL = "GLOBAL";
    static final String OWN = "OWN";

    static String scopeOf(String role) {
        return "ADMIN".equals(role) ? GLOBAL : OWN;
    }
}
