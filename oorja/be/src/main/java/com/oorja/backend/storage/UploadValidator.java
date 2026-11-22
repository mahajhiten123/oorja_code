package com.oorja.backend.storage;

import com.oorja.backend.exception.ApiException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Validates uploads before anything is written to any storage backend.
 *
 * Only PDF / JPG / JPEG / PNG is accepted. A client can send any
 * {@code Content-Type} header it likes, so the declared type alone is not
 * trusted: the extension, the declared MIME type, AND the file's actual magic
 * bytes must all agree.
 */
@Component
public class UploadValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".pdf", ".jpg", ".jpeg", ".png");

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/jpeg", "image/jpg", "image/png");

    /** Magic-byte (file signature) prefixes for each allowed type. */
    private static final byte[] SIG_PDF = {0x25, 0x50, 0x44, 0x46}; // %PDF
    private static final byte[] SIG_PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] SIG_JPG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    /**
     * Runs every check and returns the safe, generated object name
     * (a random UUID plus the validated extension). The original filename is
     * never used as part of the storage key — it's kept only as metadata on
     * the database row.
     */
    public String validateAndBuildObjectName(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Uploaded file is empty.");
        }

        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String ext = extensionOf(original);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw ApiException.badRequest("Unsupported file type. Only PDF, JPG and PNG files are allowed.");
        }

        String declaredType = file.getContentType() != null ? file.getContentType().toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_CONTENT_TYPES.contains(declaredType)) {
            throw ApiException.badRequest("Unsupported file type. Only PDF, JPG and PNG files are allowed.");
        }

        byte[] header = readHeader(file, 8);
        if (!matchesSignature(header, ext)) {
            throw ApiException.badRequest(
                    "This file doesn't look like a valid " + ext.substring(1).toUpperCase(Locale.ROOT)
                            + " — its content doesn't match its extension.");
        }

        return UUID.randomUUID() + ext;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    private static byte[] readHeader(MultipartFile file, int maxBytes) {
        try (InputStream in = file.getInputStream()) {
            byte[] buf = new byte[maxBytes];
            int read = in.read(buf);
            return read > 0 ? Arrays.copyOf(buf, read) : new byte[0];
        } catch (IOException e) {
            throw ApiException.badRequest("Couldn't read the uploaded file.");
        }
    }

    private static boolean matchesSignature(byte[] header, String ext) {
        return switch (ext) {
            case ".pdf" -> startsWith(header, SIG_PDF);
            case ".png" -> startsWith(header, SIG_PNG);
            case ".jpg", ".jpeg" -> startsWith(header, SIG_JPG);
            default -> false;
        };
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}
