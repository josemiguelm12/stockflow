package com.stockflow.document.domain;

import java.time.Instant;
import java.util.UUID;

/** Immutable list/read projection that intentionally excludes storage paths. */
public record DocumentMetadata(UUID id, UUID ownerId, String originalFilename, String mediaType, long sizeBytes,
                               Instant uploadedAt) {
}
