package com.oorja.backend.repository;

import com.oorja.backend.entity.Payment;
import com.oorja.backend.entity.enums.PaymentStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByRazorpayOrderId(String razorpayOrderId);
    Optional<Payment> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);
    List<Payment> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /** Admin console -> Payments: the full ledger, newest first. */
    List<Payment> findAllByOrderByCreatedAtDesc();

    /** Paginated variant for the admin console list — see PaymentService#findAllForAdmin. */
    Page<Payment> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** Admin console -> a booking's detail view: which payment(s) funded it, newest first. */
    List<Payment> findByBookingIdOrderByCreatedAtDesc(Long bookingId);

    /**
     * Row-locking variant used whenever something is about to act on
     * "is this payment already linked to a booking" — e.g. the customer's
     * own POST /bookings call and the webhook's auto-creation fallback can
     * race each other, and this lock is what makes only one of them win.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.razorpayOrderId = :razorpayOrderId")
    Optional<Payment> findByRazorpayOrderIdForUpdate(String razorpayOrderId);

    /** Locked read by primary key - used where the caller already has the payment's id (replay, hold expiry). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> findByIdForUpdate(@Param("id") Long id);

    /** The still-payable order(s) for a quotation, newest first (at most one exists - see uq_payments_quotation_open). */
    List<Payment> findByQuotationIdAndStatusInOrderByCreatedAtDesc(Long quotationId, java.util.Collection<PaymentStatus> statuses);

    /** Ids of unpaid slot holds that have lapsed - the expiry job releases each one under its own row lock. */
    @Query("select p.id from Payment p where p.slotHoldState = com.oorja.backend.entity.enums.SlotHoldState.HELD " +
            "and p.slotHoldExpiresAt < :now order by p.slotHoldExpiresAt")
    List<Long> findExpiredHoldIds(@Param("now") java.time.LocalDateTime now, Pageable pageable);

    @Query("select coalesce(max(p.id), 0) from Payment p")
    Long findMaxId();
}
