package com.studyflow.library.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes under {@code STORAGE_LOCAL_ROOT}, namespaced {@code users/{userId}/{documentId}/...}
 * (see specs/05-library-and-storage.md). Fails fast at boot if the root is unset or unwritable.
 * Default provider — active unless {@code studyflow.storage.provider=supabase} (see
 * {@link SupabaseStorageProvider}, used on free hosts with ephemeral disk).
 */
@Component
@ConditionalOnProperty(name = "studyflow.storage.provider", havingValue = "local", matchIfMissing = true)
public class LocalDiskStorageProvider implements StorageProvider {

    private final Path root;

    public LocalDiskStorageProvider(@Value("${studyflow.storage.local-root}") String rootPath) {
        this.root = Path.of(rootPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("STORAGE_LOCAL_ROOT is not writable: " + rootPath, e);
        }
        if (!Files.isWritable(root)) {
            throw new IllegalStateException("STORAGE_LOCAL_ROOT is not writable: " + rootPath);
        }
    }

    @Override
    public String store(byte[] content, String key) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store file at " + key, e);
        }
        return key;
    }

    @Override
    public InputStream retrieve(String storageKey) {
        try {
            return Files.newInputStream(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read file at " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete file at " + storageKey, e);
        }
    }

    @Override
    public String providerName() {
        return "LOCAL_DISK";
    }

    private Path resolve(String key) {
        // Never let a caller-influenced key (built from an uploaded filename) escape the root.
        Path resolved = root.resolve(key).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Storage key escapes storage root: " + key);
        }
        return resolved;
    }
}
