package com.stockflow.document.application;

import com.stockflow.document.domain.Document;
import com.stockflow.document.domain.DocumentMetadata;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {
    void save(Document document);
    Optional<Document> findActiveById(UUID id);
    Optional<Document> lockActiveById(UUID id);
    List<DocumentMetadata> findActiveByOwnerId(UUID ownerId);
    List<DocumentMetadata> findAllActive();
    boolean markDeleted(UUID id, Instant deletedAt);
}
