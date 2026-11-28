package com.stockflow.report.adapter.out.persistence;

import com.stockflow.report.application.ReportGranularity;
import com.stockflow.report.application.ReportQueries;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

/**
 * COUNT/AVG/MIN/MAX y GROUP BY en PostgreSQL. {@code date_trunc} recibe una unidad de la lista cerrada de
 * {@link ReportGranularity}; el filtro de propietario se añade como fragmento constante con parámetro.
 */
@Repository
class JdbcReportQueries implements ReportQueries {

    private static final String REQUESTS = """
            SELECT date_trunc(CAST(:unit AS text), requested_at AT TIME ZONE 'UTC') AS period, status, count(*) AS total
            FROM permission_requests
            WHERE requested_at >= :from AND requested_at < :to%s
            GROUP BY period, status
            ORDER BY period, status
            """;

    private static final String APPROVALS = """
            SELECT date_trunc(CAST(:unit AS text), approved_at AT TIME ZONE 'UTC') AS period,
                   count(*) AS approved,
                   avg(EXTRACT(EPOCH FROM (approved_at - submitted_at))) AS avg_seconds,
                   min(EXTRACT(EPOCH FROM (approved_at - submitted_at))) AS min_seconds,
                   max(EXTRACT(EPOCH FROM (approved_at - submitted_at))) AS max_seconds
            FROM purchase_orders
            WHERE approved_at >= :from AND approved_at < :to
              AND submitted_at IS NOT NULL%s
            GROUP BY period
            ORDER BY period
            """;

    private final JdbcClient jdbc;

    JdbcReportQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RequestGroup> permissionRequests(Instant from, Instant to, ReportGranularity granularity, UUID ownerId) {
        var statement = jdbc.sql(REQUESTS.formatted(ownerId == null ? "" : " AND requested_by = :owner"))
                .param("unit", granularity.unit())
                .param("from", utc(from))
                .param("to", utc(to));
        if (ownerId != null) {
            statement = statement.param("owner", ownerId);
        }
        return statement.query((rs, row) -> new RequestGroup(
                period(rs.getObject("period", LocalDateTime.class)), rs.getString("status"), rs.getLong("total"))).list();
    }

    @Override
    public List<ApprovalGroup> approvalEfficiency(Instant from, Instant to, ReportGranularity granularity,
                                                  UUID ownerId) {
        var statement = jdbc.sql(APPROVALS.formatted(ownerId == null ? "" : "\n  AND owner_user_id = :owner"))
                .param("unit", granularity.unit())
                .param("from", utc(from))
                .param("to", utc(to));
        if (ownerId != null) {
            statement = statement.param("owner", ownerId);
        }
        return statement.query((rs, row) -> new ApprovalGroup(
                period(rs.getObject("period", LocalDateTime.class)), rs.getLong("approved"),
                rs.getDouble("avg_seconds"), rs.getDouble("min_seconds"), rs.getDouble("max_seconds"))).list();
    }

    private static Instant period(LocalDateTime value) {
        return value.toInstant(ZoneOffset.UTC);
    }
}
