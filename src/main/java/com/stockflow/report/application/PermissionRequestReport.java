package com.stockflow.report.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** RF-REP-01: ¿cuántas solicitudes de permiso hay por estado y período? ADMIN global; STANDARD solo las propias. */
@Service
public class PermissionRequestReport {

    private final ReportQueries queries;
    private final Clock clock;

    PermissionRequestReport(ReportQueries queries, Clock clock) {
        this.queries = queries;
        this.clock = clock;
    }

    /** El actor y su rol salen de la sesión autenticada; nunca de la petición. */
    @Transactional(readOnly = true)
    public ReportResult<ReportQueries.RequestGroup> generate(UUID actorId, String role, String desde, String hasta,
                                                             String granularity) {
        ReportRange range = ReportRange.resolve(desde, hasta, clock.instant());
        ReportGranularity g = ReportGranularity.parse(granularity);
        UUID owner = "ADMIN".equals(role) ? null : actorId;
        return new ReportResult<>(range.from(), range.to(), g, ReportResult.scopeOf(role),
                queries.permissionRequests(range.from(), range.to(), g, owner));
    }
}
