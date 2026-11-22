package com.oorja.backend.service;

import com.oorja.backend.entity.BookingAttachment;
import com.oorja.backend.repository.BookingAttachmentRepository;
import com.oorja.backend.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Removes the actual stored objects for attachments that were soft-deleted
 * more than {@code app.attachments.retention-days} ago.
 *
 * The database row is deliberately kept forever as the audit record — only the
 * bytes go. Until the retention window elapses, a mistaken deletion can be
 * undone by clearing {@code deleted_at}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AttachmentPurgeJob {

    private final BookingAttachmentRepository attachmentRepository;
    private final StorageProvider storageProvider;

    /** How long a soft-deleted file's bytes are retained before being destroyed. */
    @Value("${app.attachments.retention-days:30}")
    private int retentionDays;

    /** Runs nightly at 03:15 server time. */
    @Scheduled(cron = "${app.attachments.purge-cron:0 15 3 * * *}")
    @Transactional
    public void purgeDeletedAttachments() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<BookingAttachment> purgeable = attachmentRepository.findPurgeable(cutoff);
        if (purgeable.isEmpty()) return;

        int purged = 0;
        for (BookingAttachment attachment : purgeable) {
            try {
                storageProvider.delete(attachment.getStorageKey());
                attachment.setPurgedAt(LocalDateTime.now());
                attachmentRepository.save(attachment);
                purged++;
            } catch (Exception e) {
                // Leave purgedAt null so the next run retries this one.
                log.warn("Couldn't purge attachment {} (key {}): {}",
                        attachment.getId(), attachment.getStorageKey(), e.getMessage());
            }
        }
        log.info("Attachment purge complete: {}/{} objects removed (deleted before {}).",
                purged, purgeable.size(), cutoff);
    }
}
