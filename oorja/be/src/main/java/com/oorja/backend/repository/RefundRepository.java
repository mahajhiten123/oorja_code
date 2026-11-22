package com.oorja.backend.repository;

import com.oorja.backend.entity.Refund;
import com.oorja.backend.entity.enums.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findAllByOrderByRequestedAtDesc();

    /** Paginated variant for the admin console list — see RefundService#findAll. */
    Page<Refund> findAllByOrderByRequestedAtDesc(Pageable pageable);

    List<Refund> findByCustomerIdOrderByRequestedAtDesc(Long customerId);

    List<Refund> findByStatusInOrderByRequestedAtDesc(List<RefundStatus> statuses);

    Optional<Refund> findByRefundNumber(String refundNumber);

    Optional<Refund> findByRazorpayRefundId(String razorpayRefundId);

    List<Refund> findByBookingIdOrderByRequestedAtDesc(Long bookingId);

    /**
     * The one in-flight (or already paid) refund for a booking, if any.
     * Withdrawn/rejected rows deliberately don't count — see the partial
     * unique index {@code uq_refunds_booking_active} in V17.
     */
    @Query("""
           select r from Refund r
           where r.booking.id = :bookingId
             and r.status in (com.oorja.backend.entity.enums.RefundStatus.REQUESTED,
                              com.oorja.backend.entity.enums.RefundStatus.UNDER_REVIEW,
                              com.oorja.backend.entity.enums.RefundStatus.APPROVED,
                              com.oorja.backend.entity.enums.RefundStatus.PROCESSING,
                              com.oorja.backend.entity.enums.RefundStatus.COMPLETED,
                              com.oorja.backend.entity.enums.RefundStatus.FAILED)
           """)
    Optional<Refund> findActiveByBookingId(@Param("bookingId") Long bookingId);

    /** How much of a payment we've already promised back — guards against over-refunding. */
    @Query("""
           select coalesce(sum(r.amountPaise), 0) from Refund r
           where r.payment.id = :paymentId
             and r.status in (com.oorja.backend.entity.enums.RefundStatus.APPROVED,
                              com.oorja.backend.entity.enums.RefundStatus.PROCESSING,
                              com.oorja.backend.entity.enums.RefundStatus.COMPLETED)
           """)
    long sumCommittedForPayment(@Param("paymentId") Long paymentId);

    /** Row-locking read used by every state transition, so two callers can't both act on one refund. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") Long id);

    /** Approved-but-not-yet-sent, or failed-and-due-a-retry. The dispatcher's work queue. */
    @Query("""
           select r from Refund r
           where r.status in (com.oorja.backend.entity.enums.RefundStatus.APPROVED,
                              com.oorja.backend.entity.enums.RefundStatus.FAILED)
             and (r.nextAttemptAt is null or r.nextAttemptAt <= :now)
             and r.attemptCount < :maxAttempts
           order by r.requestedAt asc
           """)
    List<Refund> findDueForDispatch(@Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts);

    /** Sent to the gateway but never confirmed by a webhook — the reconciliation job polls these. */
    @Query("""
           select r from Refund r
           where r.status = com.oorja.backend.entity.enums.RefundStatus.PROCESSING
             and r.initiatedAt < :staleBefore
           order by r.initiatedAt asc
           """)
    List<Refund> findStaleProcessing(@Param("staleBefore") LocalDateTime staleBefore);

    long countByStatus(RefundStatus status);

    @Query("select coalesce(max(r.id), 0) from Refund r")
    Long findMaxId();
}
