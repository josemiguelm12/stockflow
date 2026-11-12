package com.stockflow.document.domain;

public class DocumentAlreadyDeletedException extends RuntimeException {
    public DocumentAlreadyDeletedException() {
        super("Document is already deleted");
    }
}
