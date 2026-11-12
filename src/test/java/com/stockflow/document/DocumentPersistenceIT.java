package com.stockflow.document;

import com.stockflow.document.application.DocumentRepository;
import com.stockflow.document.application.FinancialExtractionDraftRepository;
import com.stockflow.document.domain.Document;
import com.stockflow.document.domain.FinancialExtractionDraft;
import com.stockflow.document.domain.FinancialExtractionProvider;
import com.stockflow.document.domain.FinancialExtractionProcessingStatus;
import com.stockflow.support.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentPersistenceIT extends AbstractPostgresIT {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DocumentRepository documents;
    @Autowired
    private FinancialExtractionDraftRepository drafts;

    @Test
    void v4CreatesDocumentAndFinancialDraftTables() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '4'", Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'public' AND table_name IN ('documents', 'financial_extraction_drafts')",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void repositoryHidesSoftDeletedDocumentButKeepsItsRow() {
        UUID owner = insertUser();
        Document document = document(owner);
        documents.save(document);

        assertThat(documents.findActiveById(document.id())).isPresent();
        assertThat(documents.markDeleted(document.id(), NOW.plusSeconds(1))).isTrue();

        assertThat(documents.findActiveById(document.id())).isEmpty();
        assertThat(documents.findActiveByOwnerId(owner)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM documents WHERE id = ? AND deleted_at IS NOT NULL",
                Integer.class, document.id())).isOne();
    }

    @Test
    void draftMappingAndUniqueDocumentRelationshipArePersisted() {
        UUID owner = insertUser();
        Document document = document(owner);
        documents.save(document);
        FinancialExtractionDraft draft = FinancialExtractionDraft.start(UUID.randomUUID(), document.id(),
                FinancialExtractionProvider.MINDEE_V2, UUID.randomUUID(), NOW);
        draft.complete("Supplier", "INV-1", LocalDate.of(2026, 11, 8), "USD", new BigDecimal("1.25"),
                NOW.plusSeconds(1));
        drafts.save(draft);

        FinancialExtractionDraft found = drafts.findByDocumentId(document.id()).orElseThrow();
        assertThat(found.processingStatus()).isEqualTo(FinancialExtractionProcessingStatus.EXTRACTED);
        assertThat(found.currency()).contains("USD");
        assertThat(found.totalAmount()).contains(new BigDecimal("1.2500"));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO financial_extraction_drafts (id, document_id, provider, model_id, processing_status,
                  review_status, created_at, updated_at)
                VALUES (?, ?, 'MINDEE_V2', ?, 'FAILED', 'PENDING_REVIEW', now(), now())
                """, UUID.randomUUID(), document.id(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_financial_extraction_drafts_document");
    }

    private Document document(UUID owner) {
        return Document.create(UUID.randomUUID(), owner, "invoice.pdf", "application/pdf", 12,
                UUID.randomUUID(), NOW);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email_normalized, password_hash, role, account_status, activation_completed_at,
                  password_reset_required, failed_login_attempts, locked_until, created_at, updated_at)
                VALUES (?, ?, 'hash', 'STANDARD', 'ACTIVE', now(), false, 0, NULL, now(), now())
                """, id, id + "@example.test");
        return id;
    }
}
