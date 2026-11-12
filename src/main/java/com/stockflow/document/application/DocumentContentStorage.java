package com.stockflow.document.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Binary storage boundary. Logical deletion deliberately does not request physical deletion. */
public interface DocumentContentStorage {
    void store(UUID storageKey, InputStream content) throws IOException;
    InputStream open(UUID storageKey) throws IOException;
    boolean exists(UUID storageKey) throws IOException;
    /** Used only to compensate a failed upload before its metadata transaction commits. */
    void discard(UUID storageKey) throws IOException;
}
