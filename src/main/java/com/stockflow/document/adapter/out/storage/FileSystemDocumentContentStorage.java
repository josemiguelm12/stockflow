package com.stockflow.document.adapter.out.storage;

import com.stockflow.document.application.DocumentContentStorage;
import com.stockflow.shared.config.DocumentProperties;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Component
public class FileSystemDocumentContentStorage implements DocumentContentStorage {
    private final Path root;
    private final long maxSizeBytes;

    public FileSystemDocumentContentStorage(DocumentProperties properties) {
        this.root = properties.storagePath().toAbsolutePath().normalize();
        this.maxSizeBytes = properties.maxSizeBytes();
    }

    @Override
    public void store(UUID key, InputStream content) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("storage root must not be a symbolic link");
        Path temporary = Files.createTempFile(root, key + ".", ".tmp");
        try {
            copyLimited(content, temporary);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temporary, target); }
        } catch (IOException ex) {
            Files.deleteIfExists(temporary);
            throw ex;
        }
    }

    @Override public InputStream open(UUID key) throws IOException {
        Path path = resolve(key);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new FileNotFoundException();
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
    }
    @Override public boolean exists(UUID key) throws IOException { return Files.isRegularFile(resolve(key), LinkOption.NOFOLLOW_LINKS); }
    @Override public void discard(UUID key) throws IOException { Files.deleteIfExists(resolve(key)); }

    private void copyLimited(InputStream content, Path temporary) throws IOException {
        long written = 0;
        try (InputStream input = content; OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                written += count;
                if (written > maxSizeBytes) throw new IOException("document exceeds configured size");
                output.write(buffer, 0, count);
            }
            if (written == 0) throw new IOException("document is empty");
        }
    }

    private Path resolve(UUID key) throws IOException {
        if (key == null) throw new IOException("missing storage key");
        Path path = root.resolve(key.toString()).normalize();
        if (!path.startsWith(root) || !root.equals(path.getParent())) throw new IOException("invalid storage key");
        return path;
    }
}
