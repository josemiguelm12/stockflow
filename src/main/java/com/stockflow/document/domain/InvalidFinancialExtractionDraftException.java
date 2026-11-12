package com.stockflow.document.domain;

public class InvalidFinancialExtractionDraftException extends RuntimeException {
    public InvalidFinancialExtractionDraftException(String message) {
        super(message);
    }
}
