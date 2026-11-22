package com.oorja.backend.storage;

import com.oorja.backend.exception.ApiException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;

/**
 * Stores files in an S3-compatible object store — AWS S3, Cloudflare R2,
 * MinIO, Backblaze B2, etc. This is the production path: durable storage that
 * every app instance shares, so nothing is lost when a server is replaced and
 * uploads are visible across a horizontally-scaled deployment.
 *
 * For Cloudflare R2, set:
 * <pre>
 *   app.storage.provider=s3
 *   app.storage.s3.endpoint=https://&lt;account-id&gt;.r2.cloudflarestorage.com
 *   app.storage.s3.region=auto
 *   app.storage.s3.bucket=oorja-uploads
 * </pre>
 *
 * The bucket must stay private. Files are streamed back through our own
 * authenticated endpoints so that booking-ownership checks still apply — we
 * deliberately do not hand out public object URLs.
 */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "s3")
@RequiredArgsConstructor
@Slf4j
public class S3StorageProvider implements StorageProvider {

    private final UploadValidator uploadValidator;

    @Value("${app.storage.s3.bucket:}")
    private String bucket;

    @Value("${app.storage.s3.region:auto}")
    private String region;

    /** Leave blank for AWS S3; set to the R2/MinIO endpoint otherwise. */
    @Value("${app.storage.s3.endpoint:}")
    private String endpoint;

    @Value("${app.storage.s3.access-key:}")
    private String accessKey;

    @Value("${app.storage.s3.secret-key:}")
    private String secretKey;

    /**
     * R2 and MinIO need path-style addressing; AWS S3 works with either but
     * prefers virtual-hosted style.
     */
    @Value("${app.storage.s3.path-style:true}")
    private boolean pathStyle;

    private S3Client client;

    @PostConstruct
    public void init() {
        if (bucket.isBlank()) {
            throw new IllegalStateException(
                    "app.storage.provider=s3 but app.storage.s3.bucket (env: STORAGE_S3_BUCKET) is not set.");
        }
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException(
                    "app.storage.provider=s3 but STORAGE_S3_ACCESS_KEY / STORAGE_S3_SECRET_KEY are not set.");
        }

        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(pathStyle);

        if (!endpoint.isBlank()) {
            builder = builder.endpointOverride(URI.create(endpoint));
        }

        this.client = builder.build();
        log.info("Object storage ready: bucket={} region={} endpoint={}",
                bucket, region, endpoint.isBlank() ? "(aws default)" : endpoint);
    }

    /**
     * Retries a transient failure (5xx, throttling, timeout — see
     * {@link #isRetryable}) up to 3 times with a short backoff, so a
     * momentary R2/S3 blip doesn't fail the whole upload request. Each retry
     * generates a fresh random object name (see UploadValidator), so a
     * false-negative retry (request actually landed, only the response was
     * lost) can leave one harmless orphaned object under the old name — never
     * a correctness problem, since only the key this method actually returns
     * is ever persisted to the database.
     */
    @Override
    @Retryable(
            retryFor = RetryableStorageException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 300, multiplier = 2)
    )
    public String store(MultipartFile file, String keyPrefix) {
        String objectName = uploadValidator.validateAndBuildObjectName(file);
        String storageKey = keyPrefix + "/" + objectName;

        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .contentType(file.getContentType())
                    .contentLength(file.getSize())
                    .build();

            client.putObject(request, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
            return storageKey;
        } catch (IOException e) {
            // Failure reading the already-buffered upload itself, not a
            // network call to storage - retrying won't help.
            log.error("Upload to object storage failed for key {} (reading upload): {}", storageKey, e.getMessage());
            throw new ApiException("Couldn't save the uploaded file. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (S3Exception e) {
            if (isRetryable(e)) {
                log.warn("Transient object-storage failure for key {}, will retry: {}", storageKey, e.getMessage());
                throw new RetryableStorageException(e);
            }
            log.error("Upload to object storage failed for key {}: {}", storageKey, e.getMessage());
            throw new ApiException("Couldn't save the uploaded file. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Runs once every retry attempt in {@link #store} has been exhausted. */
    @Recover
    public String recoverStore(RetryableStorageException e, MultipartFile file, String keyPrefix) {
        log.error("Upload to object storage failed after retries (prefix={}): {}", keyPrefix, e.getCause().getMessage());
        throw new ApiException("Couldn't save the uploaded file. Please try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    @Retryable(
            retryFor = RetryableStorageException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 300, multiplier = 2)
    )
    public Resource load(String storageKey) {
        try {
            var stream = client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build());
            return new InputStreamResource(stream);
        } catch (NoSuchKeyException e) {
            throw ApiException.notFound("File not found.");
        } catch (S3Exception e) {
            if (isRetryable(e)) {
                log.warn("Transient object-storage failure reading key {}, will retry: {}", storageKey, e.getMessage());
                throw new RetryableStorageException(e);
            }
            log.error("Object storage read failed for key {}: {}", storageKey, e.getMessage());
            throw new ApiException("Couldn't load this file. Please try again.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** Runs once every retry attempt in {@link #load} has been exhausted. */
    @Recover
    public Resource recoverLoad(RetryableStorageException e, String storageKey) {
        log.error("Object storage read failed after retries for key {}: {}", storageKey, e.getCause().getMessage());
        throw new ApiException("Couldn't load this file. Please try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * A 5xx, a throttling error, or a request-level timeout is worth
     * retrying; a 4xx (bad bucket, access denied, bad request) never is —
     * retrying it just burns attempts and delays the real fix.
     */
    private boolean isRetryable(S3Exception e) {
        int status = e.statusCode();
        if (status >= 500) {
            return true;
        }
        String code = e.awsErrorDetails() != null && e.awsErrorDetails().errorCode() != null
                ? e.awsErrorDetails().errorCode() : "";
        return code.equalsIgnoreCase("RequestTimeout")
                || code.equalsIgnoreCase("SlowDown")
                || code.equalsIgnoreCase("ServiceUnavailable")
                || code.equalsIgnoreCase("Throttling")
                || code.equalsIgnoreCase("ThrottlingException");
    }

    /** Unchecked signal used only to trigger the {@code @Retryable} methods above; never escapes this class. */
    private static class RetryableStorageException extends RuntimeException {
        RetryableStorageException(Throwable cause) {
            super(cause);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build());
        } catch (S3Exception e) {
            log.warn("Couldn't delete object {}: {}", storageKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            return false;
        }
    }
}
