package com.oorja.backend.storage;

import com.oorja.backend.exception.ApiException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stores files on the local filesystem under {@code app.upload.dir}.
 *
 * Suitable for development and single-server deployments only. Files live on
 * one machine's disk, so they're lost if that machine is replaced and are
 * invisible to any second app server. Use {@link S3StorageProvider} in
 * production ({@code app.storage.provider=s3}).
 */
@Component
@ConditionalOnExpression(
        "!'${app.storage.provider:local}'.equalsIgnoreCase('s3') && " +
        "!'${app.storage.provider:local}'.equalsIgnoreCase('r2') && " +
        "!'${app.storage.provider:local}'.equalsIgnoreCase('cloudflare')"
)
@RequiredArgsConstructor
@Slf4j
public class LocalStorageProvider implements StorageProvider {

    private final UploadValidator uploadValidator;

    @Value("${app.upload.dir}")
    private String uploadDir;

    private Path root;

    @PostConstruct
    public void init() {
        root = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create upload directory: " + root, e);
        }
        log.warn("Using LOCAL file storage at {}. This is not durable and does not work across " +
                 "multiple app servers — set app.storage.provider=s3 in production.", root);
    }

    @Override
    public String store(MultipartFile file, String keyPrefix) {
        String objectName = uploadValidator.validateAndBuildObjectName(file);
        String storageKey = keyPrefix + "/" + objectName;

        try {
            Path targetDir = resolve(keyPrefix);
            Files.createDirectories(targetDir);
            Files.copy(file.getInputStream(), targetDir.resolve(objectName));
            return storageKey;
        } catch (IOException e) {
            throw new ApiException("Couldn't save the uploaded file: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public Resource load(String storageKey) {
        try {
            Resource resource = new UrlResource(resolve(storageKey).toUri());
            if (!resource.exists() || !resource.isReadable()) {
                throw ApiException.notFound("File not found.");
            }
            return resource;
        } catch (MalformedURLException e) {
            throw ApiException.notFound("File not found.");
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            log.warn("Couldn't delete local file {}: {}", storageKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.exists(resolve(storageKey));
    }

    /** Resolves a key under the root, refusing anything that escapes it (path traversal). */
    private Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw ApiException.badRequest("Invalid file path.");
        }
        return path;
    }
}
