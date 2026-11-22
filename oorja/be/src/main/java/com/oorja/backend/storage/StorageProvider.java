package com.oorja.backend.storage;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * Abstraction over where uploaded files actually live.
 *
 * The database never stores file bytes — only a {@code storageKey} that this
 * provider knows how to resolve. Two implementations ship:
 *
 * <ul>
 *   <li>{@link LocalStorageProvider} — writes under {@code app.upload.dir}.
 *       Fine for local development and single-server deploys, but files are
 *       lost if the box dies and are invisible to a second app server.</li>
 *   <li>{@link S3StorageProvider} — S3, Cloudflare R2, or any S3-compatible
 *       bucket. This is what production should use: durable, and shared across
 *       every app instance.</li>
 * </ul>
 *
 * Select with {@code app.storage.provider=local|s3}.
 */
public interface StorageProvider {

    /**
     * Validates and stores a file under the given key prefix, returning the
     * full storage key to persist on the entity (e.g.
     * {@code bookings/2026/09/BOOK-10342/8af3….jpg}).
     */
    String store(MultipartFile file, String keyPrefix);

    /** Resolves a stored object for streaming back to the client. */
    Resource load(String storageKey);

    /**
     * Permanently removes the stored object. Callers should soft-delete the
     * database row first; this is only invoked later by the cleanup job, once
     * the retention window has passed.
     */
    void delete(String storageKey);

    /** True if the object still exists in the backing store. */
    boolean exists(String storageKey);
}
