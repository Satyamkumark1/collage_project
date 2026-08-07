package com.studyflow.library.service;

import java.io.InputStream;

/**
 * Swappable file storage (see specs/05-library-and-storage.md). This phase:
 * {@link LocalDiskStorageProvider}. Cloudinary lands later as a second implementation of the
 * same interface — no calling code changes.
 */
public interface StorageProvider {

    String store(byte[] content, String key);

    InputStream retrieve(String storageKey);

    void delete(String storageKey);

    String providerName();
}
