package com.stockflow.document.adapter.in.web;
import com.stockflow.document.domain.DocumentMetadata;
import java.time.Instant; import java.util.UUID;
record DocumentResponse(UUID id, String filename, String mediaType, long sizeBytes, Instant uploadedAt) {
 static DocumentResponse from(DocumentMetadata d) { return new DocumentResponse(d.id(),d.originalFilename(),d.mediaType(),d.sizeBytes(),d.uploadedAt()); }
}
