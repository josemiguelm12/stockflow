package com.stockflow.shared.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentPropertiesTest {

    @Test
    void defaultsKeepNonDocumentContextsRunnable() {
        DocumentProperties properties = new DocumentProperties(null, null);

        assertThat(properties.storagePath()).isEqualTo(Path.of("documents"));
        assertThat(properties.maxSizeBytes()).isEqualTo(DocumentProperties.DEFAULT_MAX_SIZE_BYTES);
    }

    @Test
    void rejectsNonPositiveDocumentSize() {
        assertThatThrownBy(() -> new DocumentProperties(Path.of("storage"), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-size-bytes");
    }
}
