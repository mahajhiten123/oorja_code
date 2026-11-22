package com.oorja.backend.repository;

import com.oorja.backend.entity.BookingAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Note: every "find" here filters out soft-deleted rows. Deleted attachments
 * stay in the table as the audit record but must never surface in the API.
 */
public interface BookingAttachmentRepository extends JpaRepository<BookingAttachment, Long> {

    List<BookingAttachment> findByBookingIdAndDeletedAtIsNull(Long bookingId);

    List<BookingAttachment> findByBookingIdInAndDeletedAtIsNull(List<Long> bookingIds);

    /** Soft-deleted rows whose retention window has elapsed and whose object is still in storage. */
    @Query("select a from BookingAttachment a " +
           "where a.deletedAt is not null and a.purgedAt is null and a.deletedAt < :cutoff")
    List<BookingAttachment> findPurgeable(@Param("cutoff") LocalDateTime cutoff);
}
