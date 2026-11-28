package com.stockflow.inventory.adapter.out.persistence;

import com.stockflow.inventory.application.PurchaseOrderRepository;
import com.stockflow.inventory.domain.PurchaseOrder;
import com.stockflow.inventory.domain.PurchaseOrderStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcPurchaseOrderRepository implements PurchaseOrderRepository {

    private static final String SELECT_BY_ID =
            "SELECT id, supplier_id, owner_user_id, state, created_at, submitted_at, approved_at"
                    + " FROM purchase_orders WHERE id = :id";

    private static final RowMapper<PurchaseOrder> ORDER = (rs, row) -> PurchaseOrder.restore(
            rs.getObject("id", UUID.class),
            rs.getObject("supplier_id", UUID.class),
            rs.getObject("owner_user_id", UUID.class),
            PurchaseOrderStatus.valueOf(rs.getString("state")),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            instant(rs.getObject("submitted_at", OffsetDateTime.class)),
            instant(rs.getObject("approved_at", OffsetDateTime.class)));

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private final JdbcClient jdbc;

    JdbcPurchaseOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PurchaseOrder order) {
        jdbc.sql("""
                INSERT INTO purchase_orders
                    (id, supplier_id, owner_user_id, state, created_at, submitted_at, approved_at)
                VALUES (:id, :supplierId, :ownerId, :state, :createdAt, :submittedAt, :approvedAt)
                ON CONFLICT (id) DO UPDATE SET state = EXCLUDED.state, submitted_at = EXCLUDED.submitted_at,
                    approved_at = EXCLUDED.approved_at
                """)
                .param("id", order.id())
                .param("supplierId", order.supplierId().orElse(null))
                .param("ownerId", order.ownerId().orElse(null))
                .param("state", order.status().name())
                .param("createdAt", utc(order.createdAt()))
                .param("submittedAt", order.submittedAt().map(i -> utc(i)).orElse(null))
                .param("approvedAt", order.approvedAt().map(i -> utc(i)).orElse(null))
                .update();
    }

    @Override
    public Optional<PurchaseOrder> findById(UUID id) {
        return jdbc.sql(SELECT_BY_ID)
                .param("id", id)
                .query(ORDER)
                .optional();
    }

    @Override
    public Optional<PurchaseOrder> lockById(UUID id) {
        return jdbc.sql(SELECT_BY_ID + " FOR UPDATE")
                .param("id", id)
                .query(ORDER)
                .optional();
    }
}
