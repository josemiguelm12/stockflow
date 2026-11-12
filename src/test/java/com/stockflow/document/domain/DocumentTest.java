package com.stockflow.document.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");

    @Test
    void documentRequiresSafePersistableMetadataAndCanOnlyBeDeletedOnce() {
        Document document = Document.create(UUID.randomUUID(), UUID.randomUUID(), "invoice.pdf", "application/pdf",
                1, UUID.randomUUID(), NOW);

        document.delete(NOW.plusSeconds(1));

        assertThat(document.deletedAt()).contains(NOW.plusSeconds(1));
        assertThatThrownBy(() -> document.delete(NOW.plusSeconds(2)))
                .isInstanceOf(DocumentAlreadyDeletedException.class);
        assertThatThrownBy(() -> Document.create(UUID.randomUUID(), UUID.randomUUID(), " ", "application/pdf",
                1, UUID.randomUUID(), NOW)).isInstanceOf(InvalidDocumentException.class);
        assertThatThrownBy(() -> Document.create(UUID.randomUUID(), UUID.randomUUID(), "a", "text/plain",
                0, UUID.randomUUID(), NOW)).isInstanceOf(InvalidDocumentException.class);
    }

    @Test
    void financialDraftRequiresModelAndValidValuesOnlyWhenExtracted() {
        FinancialExtractionDraft draft = FinancialExtractionDraft.start(UUID.randomUUID(), UUID.randomUUID(),
                FinancialExtractionProvider.MINDEE_V2, UUID.randomUUID(), NOW);

        draft.complete(" Supplier ", " INV-1 ", LocalDate.of(2026, 11, 8), "dop",
                new BigDecimal("12.34"), NOW.plusSeconds(1));

        assertThat(draft.supplierName()).contains("Supplier");
        assertThat(draft.currency()).contains("DOP");
        assertThat(draft.totalAmount()).contains(new BigDecimal("12.34"));
        draft.markReviewed(NOW.plusSeconds(2));
        assertThat(draft.reviewStatus()).isEqualTo(FinancialExtractionReviewStatus.REVIEWED);

        assertThatThrownBy(() -> FinancialExtractionDraft.restore(UUID.randomUUID(), UUID.randomUUID(),
                FinancialExtractionProvider.MINDEE_V2, null, FinancialExtractionProcessingStatus.EXTRACTED,
                FinancialExtractionReviewStatus.PENDING_REVIEW, null, null, null, null, null, NOW, NOW))
                .isInstanceOf(InvalidFinancialExtractionDraftException.class);
        assertThatThrownBy(() -> draft.complete(null, null, null, "DO", BigDecimal.ONE, NOW))
                .isInstanceOf(InvalidFinancialExtractionDraftException.class);
    }

    @Test
    void failureAndRestartClearFinancialValues() {
        FinancialExtractionDraft draft = FinancialExtractionDraft.start(UUID.randomUUID(), UUID.randomUUID(),
                FinancialExtractionProvider.MINDEE_V2, UUID.randomUUID(), NOW);
        draft.complete("Supplier", null, null, "USD", BigDecimal.ONE, NOW.plusSeconds(1));

        draft.restart(FinancialExtractionProvider.MINDEE_V2, UUID.randomUUID(), NOW.plusSeconds(2));
        draft.fail(FinancialExtractionProcessingStatus.TIMED_OUT, NOW.plusSeconds(3));

        assertThat(draft.processingStatus()).isEqualTo(FinancialExtractionProcessingStatus.TIMED_OUT);
        assertThat(draft.supplierName()).isEmpty();
        assertThat(draft.totalAmount()).isEmpty();
    }
}
