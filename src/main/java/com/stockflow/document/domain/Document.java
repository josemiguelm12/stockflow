package com.stockflow.document.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Metadata persisted for a binary stored by an outbound adapter. */
public final class Document {

    private final UUID id;
    private final UUID ownerId;
    private final String originalFilename;
    private final String mediaType;
    private final long sizeBytes;
    private final UUID storageKey;
    private final Instant uploadedAt;
    private Instant deletedAt;

    private Document(UUID id, UUID ownerId, String originalFilename, String mediaType, long sizeBytes,
                     UUID storageKey, Instant uploadedAt, Instant deletedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.originalFilename = requiredText(originalFilename, "originalFilename", 512);
        this.mediaType = requiredText(mediaType, "mediaType", 255);
        if (sizeBytes <= 0) {
            throw new InvalidDocumentException("sizeBytes must be positive");
        }
        this.sizeBytes = sizeBytes;
        this.storageKey = Objects.requireNonNull(storageKey, "storageKey");
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt");
        this.deletedAt = deletedAt;
    }

    public static Document create(UUID id, UUID ownerId, String originalFilename, String mediaType, long sizeBytes,
                                  UUID storageKey, Instant uploadedAt) {
        return new Document(id, ownerId, originalFilename, mediaType, sizeBytes, storageKey, uploadedAt, null);
    }

    public static Document restore(UUID id, UUID ownerId, String originalFilename, String mediaType, long sizeBytes,
                                   UUID storageKey, Instant uploadedAt, Instant deletedAt) {
        return new Document(id, ownerId, originalFilename, mediaType, sizeBytes, storageKey, uploadedAt, deletedAt);
    }

    public void delete(Instant now) {
        if (deletedAt != null) {
            throw new DocumentAlreadyDeletedException();
        }
        deletedAt = Objects.requireNonNull(now, "now");
    }

    public UUID id() { return id; }
    public UUID ownerId() { return ownerId; }
    public String originalFilename() { return originalFilename; }
    public String mediaType() { return mediaType; }
    public long sizeBytes() { return sizeBytes; }
    public UUID storageKey() { return storageKey; }
    public Instant uploadedAt() { return uploadedAt; }
    public Optional<Instant> deletedAt() { return Optional.ofNullable(deletedAt); }
    public boolean isDeleted() { return deletedAt != null; }

    private static String requiredText(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new InvalidDocumentException(name + " must be non-blank and at most " + maxLength + " characters");
        }
        return value;
    }
}
