package com.stockflow.document.application;

import com.stockflow.document.domain.Document;
import com.stockflow.document.domain.InvalidDocumentException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class UploadDocument {
    private final DocumentRepository documents; private final DocumentContentStorage storage;
    private final DocumentActorAuthorizer authorizer; private final Clock clock;
    public UploadDocument(DocumentRepository documents, DocumentContentStorage storage, DocumentActorAuthorizer authorizer, Clock clock) {
        this.documents=documents; this.storage=storage; this.authorizer=authorizer; this.clock=clock;
    }
    @Transactional
    public Document upload(UUID actorId, Upload upload) {
        authorizer.requireActive(actorId);
        if (upload == null || upload.content() == null || upload.sizeBytes() <= 0) throw new InvalidDocumentException("file is required and must not be empty");
        Document document = Document.create(UUID.randomUUID(), actorId, upload.filename(), upload.mediaType(), upload.sizeBytes(), UUID.randomUUID(), Instant.now(clock));
        try { storage.store(document.storageKey(), upload.content()); }
        catch (Exception ex) { throw new DocumentStorageException(ex); }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) try { storage.discard(document.storageKey()); } catch (Exception ignored) { } }
        });
        documents.save(document);
        return document;
    }
    public record Upload(String filename, String mediaType, long sizeBytes, InputStream content) { }
}
