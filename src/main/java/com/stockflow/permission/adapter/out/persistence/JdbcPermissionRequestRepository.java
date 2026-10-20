package com.stockflow.permission.adapter.out.persistence;

import com.stockflow.permission.application.PermissionRequestRepository;
import com.stockflow.permission.domain.PermissionCode;
import com.stockflow.permission.domain.PermissionRequest;
import com.stockflow.permission.domain.PermissionRequestStatus;
import com.stockflow.shared.persistence.Timestamps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcPermissionRequestRepository implements PermissionRequestRepository {

    private static final String SELECT_COLUMNS = """
            SELECT id, requested_by, permission_code, status, requested_at,
                   decided_by, decided_at, rejection_reason, applied_by, applied_at
            FROM permission_requests
            """;

    private static final RowMapper<PermissionRequest> REQUEST = (rs, row) -> PermissionRequest.restore(
            rs.getObject("id", UUID.class),
            rs.getObject("requested_by", UUID.class),
            PermissionCode.valueOf(rs.getString("permission_code")),
            instant(rs, "requested_at"),
            PermissionRequestStatus.valueOf(rs.getString("status")),
            rs.getObject("decided_by", UUID.class),
            instant(rs, "decided_at"),
            rs.getString("rejection_reason"),
            rs.getObject("applied_by", UUID.class),
            instant(rs, "applied_at"));

    private final JdbcClient jdbc;

    JdbcPermissionRequestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PermissionRequest request) {
        jdbc.sql("""
                INSERT INTO permission_requests (id, requested_by, permission_code, status, requested_at,
                                                 decided_by, decided_at, rejection_reason, applied_by, applied_at)
                VALUES (:id, :requestedBy, :permissionCode, :status, :requestedAt,
                        :decidedBy, :decidedAt, :rejectionReason, :appliedBy, :appliedAt)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status,
                                               decided_by = EXCLUDED.decided_by,
                                               decided_at = EXCLUDED.decided_at,
                                               rejection_reason = EXCLUDED.rejection_reason,
                                               applied_by = EXCLUDED.applied_by,
                                               applied_at = EXCLUDED.applied_at
                """)
                .param("id", request.id())
                .param("requestedBy", request.requestedBy())
                .param("permissionCode", request.permissionCode().name())
                .param("status", request.status().name())
                .param("requestedAt", utc(request.requestedAt()))
                .param("decidedBy", request.decidedBy().orElse(null))
                .param("decidedAt", request.decidedAt().map(Timestamps::utc).orElse(null))
                .param("rejectionReason", request.rejectionReason().orElse(null))
                .param("appliedBy", request.appliedBy().orElse(null))
                .param("appliedAt", request.appliedAt().map(Timestamps::utc).orElse(null))
                .update();
    }

    @Override
    public Optional<PermissionRequest> findById(UUID id) {
        return jdbc.sql(SELECT_COLUMNS + "WHERE id = :id")
                .param("id", id)
                .query(REQUEST)
                .optional();
    }

    @Override
    public Optional<PermissionRequest> lockById(UUID id) {
        return jdbc.sql(SELECT_COLUMNS + "WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(REQUEST)
                .optional();
    }

    @Override
    public List<PermissionRequest> findPending() {
        return jdbc.sql(SELECT_COLUMNS + "WHERE status = 'PENDING' ORDER BY requested_at ASC, id ASC")
                .query(REQUEST)
                .list();
    }

    @Override
    public List<PermissionRequest> findPendingByRequester(UUID requesterId) {
        return jdbc.sql(SELECT_COLUMNS + """
                WHERE status = 'PENDING' AND requested_by = :requestedBy
                ORDER BY requested_at ASC, id ASC
                """)
                .param("requestedBy", requesterId)
                .query(REQUEST)
                .list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
