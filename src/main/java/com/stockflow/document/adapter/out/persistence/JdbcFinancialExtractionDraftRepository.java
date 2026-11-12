package com.stockflow.document.adapter.out.persistence;

import com.stockflow.document.application.FinancialExtractionDraftRepository;
import com.stockflow.document.domain.FinancialExtractionDraft;
import com.stockflow.document.domain.FinancialExtractionProcessingStatus;
import com.stockflow.document.domain.FinancialExtractionProvider;
import com.stockflow.document.domain.FinancialExtractionReviewStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcFinancialExtractionDraftRepository implements FinancialExtractionDraftRepository {

    private static final String COLUMNS = """
            id, document_id, provider, model_id, processing_status, review_status, supplier_name, invoice_number,
            invoice_date, currency, total_amount, created_at, updated_at
            """;

    private static final RowMapper<FinancialExtractionDraft> DRAFT = (rs, row) -> FinancialExtractionDraft.restore(
            rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class),
            FinancialExtractionProvider.valueOf(rs.getString("provider")),
            rs.getObject("model_id", UUID.class),
            FinancialExtractionProcessingStatus.valueOf(rs.getString("processing_status")),
            FinancialExtractionReviewStatus.valueOf(rs.getString("review_status")),
            rs.getString("supplier_name"),
            rs.getString("invoice_number"),
            rs.getObject("invoice_date", LocalDate.class),
            rs.getString("currency"),
            rs.getObject("total_amount", BigDecimal.class),
            instant(rs.getObject("created_at", OffsetDateTime.class)),
            instant(rs.getObject("updated_at", OffsetDateTime.class)));

    private final JdbcClient jdbc;

    JdbcFinancialExtractionDraftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(FinancialExtractionDraft draft) {
        jdbc.sql("""
                INSERT INTO financial_extraction_drafts (
                    id, document_id, provider, model_id, processing_status, review_status, supplier_name,
                    invoice_number, invoice_date, currency, total_amount, created_at, updated_at)
                VALUES (:id, :documentId, :provider, :modelId, :processingStatus, :reviewStatus, :supplierName,
                        :invoiceNumber, :invoiceDate, :currency, :totalAmount, :createdAt, :updatedAt)
                ON CONFLICT (document_id) DO UPDATE SET
                    provider = EXCLUDED.provider,
                    model_id = EXCLUDED.model_id,
                    processing_status = EXCLUDED.processing_status,
                    review_status = EXCLUDED.review_status,
                    supplier_name = EXCLUDED.supplier_name,
                    invoice_number = EXCLUDED.invoice_number,
                    invoice_date = EXCLUDED.invoice_date,
                    currency = EXCLUDED.currency,
                    total_amount = EXCLUDED.total_amount,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("id", draft.id())
                .param("documentId", draft.documentId())
                .param("provider", draft.provider().name())
                .param("modelId", draft.modelId().orElse(null))
                .param("processingStatus", draft.processingStatus().name())
                .param("reviewStatus", draft.reviewStatus().name())
                .param("supplierName", draft.supplierName().orElse(null))
                .param("invoiceNumber", draft.invoiceNumber().orElse(null))
                .param("invoiceDate", draft.invoiceDate().orElse(null))
                .param("currency", draft.currency().orElse(null))
                .param("totalAmount", draft.totalAmount().orElse(null))
                .param("createdAt", utc(draft.createdAt()))
                .param("updatedAt", utc(draft.updatedAt()))
                .update();
    }

    @Override
    public Optional<FinancialExtractionDraft> findByDocumentId(UUID documentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM financial_extraction_drafts WHERE document_id = :documentId")
                .param("documentId", documentId)
                .query(DRAFT)
                .optional();
    }

    @Override
    public Optional<FinancialExtractionDraft> lockByDocumentId(UUID documentId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM financial_extraction_drafts WHERE document_id = :documentId FOR UPDATE")
                .param("documentId", documentId)
                .query(DRAFT)
                .optional();
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
