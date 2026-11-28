package com.stockflow.report.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Agregaciones calculadas por el motor de datos (RF-REP-03). {@code ownerId} nulo = alcance global; con valor, solo
 * filas de ese usuario. Ningún método devuelve filas individuales.
 */
public interface ReportQueries {

    record RequestGroup(Instant periodStart, String status, long count) {
    }

    /** Duraciones en segundos (approved_at − submitted_at). */
    record ApprovalGroup(Instant periodStart, long approvedCount, double averageSeconds, double minSeconds,
                         double maxSeconds) {
    }

    /** Por {@code requested_at} en {@code [from, to)}; omite grupos sin filas. */
    List<RequestGroup> permissionRequests(Instant from, Instant to, ReportGranularity granularity, UUID ownerId);

    /** Por {@code approved_at} en {@code [from, to)}; solo órdenes con submitted_at y approved_at. */
    List<ApprovalGroup> approvalEfficiency(Instant from, Instant to, ReportGranularity granularity, UUID ownerId);
}
