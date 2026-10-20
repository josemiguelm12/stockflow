package com.stockflow.permission.adapter.out.persistence;

import com.stockflow.permission.application.GrantedPermission;
import com.stockflow.permission.application.UserPermissionRepository;
import com.stockflow.permission.domain.PermissionCode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcUserPermissionRepository implements UserPermissionRepository {

    private final JdbcClient jdbc;

    JdbcUserPermissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** INSERT explícito: las restricciones únicas rechazan duplicados y el error llega al llamador. */
    @Override
    public void save(GrantedPermission permission) {
        jdbc.sql("""
                INSERT INTO user_permissions (id, user_id, permission_code, source_request_id, granted_at)
                VALUES (:id, :userId, :permissionCode, :sourceRequestId, :grantedAt)
                """)
                .param("id", permission.id())
                .param("userId", permission.userId())
                .param("permissionCode", permission.permissionCode().name())
                .param("sourceRequestId", permission.sourceRequestId())
                .param("grantedAt", utc(permission.grantedAt()))
                .update();
    }

    @Override
    public Optional<GrantedPermission> findBySourceRequestId(UUID sourceRequestId) {
        return jdbc.sql("""
                SELECT id, user_id, permission_code, source_request_id, granted_at
                FROM user_permissions WHERE source_request_id = :sourceRequestId
                """)
                .param("sourceRequestId", sourceRequestId)
                .query((rs, row) -> new GrantedPermission(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        PermissionCode.valueOf(rs.getString("permission_code")),
                        rs.getObject("source_request_id", UUID.class),
                        rs.getObject("granted_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    @Override
    public boolean existsByUserIdAndPermissionCode(UUID userId, PermissionCode permissionCode) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM user_permissions WHERE user_id = :userId AND permission_code = :permissionCode
                )
                """)
                .param("userId", userId)
                .param("permissionCode", permissionCode.name())
                .query(Boolean.class)
                .single();
    }
}
