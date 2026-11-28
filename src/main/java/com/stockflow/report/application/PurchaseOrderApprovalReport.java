package com.stockflow.report.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * RF-REP-02: ¿cuánto tardan las órdenes en pasar de SUBMITTED a APPROVED? Agrupa por {@code approved_at}; las órdenes
 * históricas sin fechas quedan excluidas. ADMIN global; STANDARD solo las órdenes de las que es propietario.
 */
@Service
public class PurchaseOrderApprovalReport {

    private final ReportQueries queries;
    private final Clock clock;

    PurchaseOrderApprovalReport(ReportQueries queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ReportResult<ReportQueries.ApprovalGroup> generate(UUID actorId, String role, String desde, String hasta,
                                                              String granularity) {
        ReportRange range = ReportRange.resolve(desde, hasta, clock.instant());
        ReportGranularity g = ReportGranularity.parse(granularity);
        UUID owner = "ADMIN".equals(role) ? null : actorId;
        return new ReportResult<>(range.from(), range.to(), g, ReportResult.scopeOf(role),
                queries.approvalEfficiency(range.from(), range.to(), g, owner));
    }
}
