package com.stockflow.inventory.adapter.out.persistence;

import com.stockflow.inventory.application.PurchaseOrderRepository;
import com.stockflow.inventory.domain.PurchaseOrder;
import com.stockflow.inventory.domain.PurchaseOrderStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcPurchaseOrderRepository implements PurchaseOrderRepository {

    private final JdbcClient jdbc;

    JdbcPurchaseOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(PurchaseOrder order) {
        jdbc.sql("""
                INSERT INTO purchase_orders (id, supplier_id, state, created_at)
                VALUES (:id, :supplierId, :state, :createdAt)
                ON CONFLICT (id) DO UPDATE SET state = EXCLUDED.state
                """)
                .param("id", order.id())
                .param("supplierId", order.supplierId().orElse(null))
                .param("state", order.status().name())
                .param("createdAt", utc(order.createdAt()))
                .update();
    }

    @Override
    public Optional<PurchaseOrder> findById(UUID id) {
        return jdbc.sql("SELECT id, supplier_id, state, created_at FROM purchase_orders WHERE id = :id")
                .param("id", id)
                .query((rs, row) -> PurchaseOrder.restore(
                        rs.getObject("id", UUID.class),
                        rs.getObject("supplier_id", UUID.class),
                        PurchaseOrderStatus.valueOf(rs.getString("state")),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .optional();
    }
}
