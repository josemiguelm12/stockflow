package com.stockflow.document.application;

import com.stockflow.document.domain.FinancialExtractionDraft;

import java.util.Optional;
import java.util.UUID;

public interface FinancialExtractionDraftRepository {
    void save(FinancialExtractionDraft draft);
    Optional<FinancialExtractionDraft> findByDocumentId(UUID documentId);
    Optional<FinancialExtractionDraft> lockByDocumentId(UUID documentId);
}
