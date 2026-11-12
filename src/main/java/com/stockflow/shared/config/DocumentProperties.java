package com.stockflow.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/** Filesystem and upload limits. Defaults keep existing non-document contexts runnable. */
@Validated
@ConfigurationProperties("stockflow.documents")
public record DocumentProperties(Path storagePath, Long maxSizeBytes) {

    public static final long DEFAULT_MAX_SIZE_BYTES = 10L * 1024 * 1024;
    public static final Path DEFAULT_STORAGE_PATH = Path.of("documents");

    public DocumentProperties {
        storagePath = storagePath == null ? DEFAULT_STORAGE_PATH : storagePath.normalize();
        maxSizeBytes = maxSizeBytes == null ? DEFAULT_MAX_SIZE_BYTES : maxSizeBytes;
        if (storagePath.toString().isBlank()) {
            throw new IllegalArgumentException("stockflow.documents.storage-path must not be blank");
        }
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("stockflow.documents.max-size-bytes must be positive");
        }
    }
}
