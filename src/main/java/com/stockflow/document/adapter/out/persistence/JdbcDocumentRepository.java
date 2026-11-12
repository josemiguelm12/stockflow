package com.stockflow.document.adapter.out.persistence;

import com.stockflow.document.application.DocumentRepository;
import com.stockflow.document.domain.Document;
import com.stockflow.document.domain.DocumentMetadata;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.stockflow.shared.persistence.Timestamps.utc;

@Repository
class JdbcDocumentRepository implements DocumentRepository {

    private static final String DOCUMENT_COLUMNS = """
            id, owner_id, original_filename, media_type, size_bytes, storage_key, uploaded_at, deleted_at
            """;
    private static final String ACTIVE_SELECT = "SELECT " + DOCUMENT_COLUMNS + " FROM documents "
            + "WHERE id = :id AND deleted_at IS NULL";

    private static final RowMapper<Document> DOCUMENT = (rs, row) -> Document.restore(
            rs.getObject("id", UUID.class),
            rs.getObject("owner_id", UUID.class),
            rs.getString("original_filename"),
            rs.getString("media_type"),
            rs.getLong("size_bytes"),
            rs.getObject("storage_key", UUID.class),
            instant(rs.getObject("uploaded_at", OffsetDateTime.class)),
            instant(rs.getObject("deleted_at", OffsetDateTime.class)));

    private static final RowMapper<DocumentMetadata> METADATA = (rs, row) -> new DocumentMetadata(
            rs.getObject("id", UUID.class),
            rs.getObject("owner_id", UUID.class),
            rs.getString("original_filename"),
            rs.getString("media_type"),
            rs.getLong("size_bytes"),
            instant(rs.getObject("uploaded_at", OffsetDateTime.class)));

    private final JdbcClient jdbc;

    JdbcDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Document document) {
        jdbc.sql("""
                INSERT INTO documents (id, owner_id, original_filename, media_type, size_bytes, storage_key,
                                       uploaded_at, deleted_at)
                VALUES (:id, :ownerId, :originalFilename, :mediaType, :sizeBytes, :storageKey, :uploadedAt, :deletedAt)
                ON CONFLICT (id) DO UPDATE SET deleted_at = EXCLUDED.deleted_at
                """)
                .param("id", document.id())
                .param("ownerId", document.ownerId())
                .param("originalFilename", document.originalFilename())
                .param("mediaType", document.mediaType())
                .param("sizeBytes", document.sizeBytes())
                .param("storageKey", document.storageKey())
                .param("uploadedAt", utc(document.uploadedAt()))
                .param("deletedAt", document.deletedAt().map(com.stockflow.shared.persistence.Timestamps::utc).orElse(null))
                .update();
    }

    @Override
    public Optional<Document> findActiveById(UUID id) {
        return jdbc.sql(ACTIVE_SELECT).param("id", id).query(DOCUMENT).optional();
    }

    @Override
    public Optional<Document> lockActiveById(UUID id) {
        return jdbc.sql(ACTIVE_SELECT + " FOR UPDATE").param("id", id).query(DOCUMENT).optional();
    }

    @Override
    public List<DocumentMetadata> findActiveByOwnerId(UUID ownerId) {
        return jdbc.sql("SELECT " + DOCUMENT_COLUMNS + " FROM documents "
                        + "WHERE owner_id = :ownerId AND deleted_at IS NULL ORDER BY uploaded_at DESC, id DESC")
                .param("ownerId", ownerId)
                .query(METADATA)
                .list();
    }

    @Override
    public List<DocumentMetadata> findAllActive() {
        return jdbc.sql("SELECT " + DOCUMENT_COLUMNS + " FROM documents "
                        + "WHERE deleted_at IS NULL ORDER BY uploaded_at DESC, id DESC")
                .query(METADATA)
                .list();
    }

    @Override
    public boolean markDeleted(UUID id, Instant deletedAt) {
        return jdbc.sql("UPDATE documents SET deleted_at = :deletedAt WHERE id = :id AND deleted_at IS NULL")
                .param("id", id)
                .param("deletedAt", utc(deletedAt))
                .update() == 1;
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
