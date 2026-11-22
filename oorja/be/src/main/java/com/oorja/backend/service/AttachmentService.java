package com.oorja.backend.service;

import com.oorja.backend.entity.BookingAttachment;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingAttachmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Deletion rules for booking attachments.
 *
 * <p><b>Customer</b> may delete a file only when all of the following hold:
 * the booking is theirs, they uploaded the file themselves, the file is not an
 * official document (service report / completion doc), and the booking has not
 * yet been completed. Once a job is completed the attachment set is part of the
 * record of that job, so it is frozen.
 *
 * <p><b>Admin</b> may delete any attachment, with an optional reason recorded.
 *
 * <p>Deletion is always soft. The row remains as the audit record —
 * {@code deleted_at}, {@code deleted_by}, {@code deleted_by_type},
 * {@code delete_reason} — and the underlying object is removed later by
 * {@link AttachmentPurgeJob} once the retention window has passed. Nothing is
 * destroyed synchronously, so an accidental delete is recoverable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentService {

    private final BookingAttachmentRepository attachmentRepository;

    /** Loads an attachment that must belong to the given customer's booking. */
    @Transactional(readOnly = true)
    public BookingAttachment getForCustomer(Long attachmentId, Long customerId) {
        BookingAttachment attachment = getLive(attachmentId);
        if (!attachment.getBooking().getCustomer().getId().equals(customerId)) {
            // Don't reveal that the file exists on someone else's booking.
            throw ApiException.notFound("Attachment not found.");
        }
        return attachment;
    }

    @Transactional(readOnly = true)
    public BookingAttachment getForAdmin(Long attachmentId) {
        return getLive(attachmentId);
    }

    @Transactional
    public void deleteAsCustomer(Long attachmentId, Long customerId) {
        BookingAttachment attachment = getForCustomer(attachmentId, customerId);

        if (attachment.isOfficialDocument()) {
            throw ApiException.badRequest(
                    "Service reports and completion documents can't be deleted. Contact support if this is wrong.");
        }

        boolean uploadedByThisCustomer = attachment.getUploadedByType() == SubjectType.CUSTOMER
                && customerId.equals(attachment.getUploadedBy());
        if (!uploadedByThisCustomer) {
            throw ApiException.badRequest("You can only delete files you uploaded yourself.");
        }

        BookingStatus status = attachment.getBooking().getStatus();
        if (status == BookingStatus.COMPLETED) {
            throw ApiException.badRequest(
                    "This booking is completed, so its files are part of the service record and can no longer be removed.");
        }

        softDelete(attachment, customerId, SubjectType.CUSTOMER, null);
    }

    @Transactional
    public void deleteAsAdmin(Long attachmentId, Long adminId, String reason) {
        BookingAttachment attachment = getLive(attachmentId);
        softDelete(attachment, adminId, SubjectType.ADMIN, reason);
    }

    private void softDelete(BookingAttachment attachment, Long actorId, SubjectType actorType, String reason) {
        attachment.setDeletedAt(LocalDateTime.now());
        attachment.setDeletedBy(actorId);
        attachment.setDeletedByType(actorType);
        attachment.setDeleteReason(reason);
        attachmentRepository.save(attachment);

        log.info("Attachment {} (booking {}) soft-deleted by {} {}{}",
                attachment.getId(), attachment.getBooking().getId(), actorType, actorId,
                reason != null ? " — " + reason : "");
    }

    private BookingAttachment getLive(Long attachmentId) {
        BookingAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> ApiException.notFound("Attachment not found."));
        if (attachment.isDeleted()) {
            throw ApiException.notFound("Attachment not found.");
        }
        return attachment;
    }
}
