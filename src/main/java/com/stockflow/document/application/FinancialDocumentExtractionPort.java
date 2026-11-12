package com.stockflow.document.application;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Outbound boundary for a financial-document provider; no provider implementation is added in P4-F1. */
public interface FinancialDocumentExtractionPort {

    ExtractionResult extract(ExtractionRequest request);

    record ExtractionRequest(UUID documentId, UUID modelId, String filename, String mediaType, InputStream content) {
    }

    record ExtractionResult(Optional<String> supplierName, Optional<String> invoiceNumber,
                            Optional<LocalDate> invoiceDate, Optional<String> currency,
                            Optional<BigDecimal> totalAmount) {
    }
}
