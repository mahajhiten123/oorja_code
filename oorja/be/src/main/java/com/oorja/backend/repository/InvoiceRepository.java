package com.oorja.backend.repository;

import com.oorja.backend.entity.Invoice;
import com.oorja.backend.entity.enums.InvoiceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    List<Invoice> findByCustomerIdOrderByIssuedAtDesc(Long customerId);

    List<Invoice> findAllByOrderByIssuedAtDesc();

    /** Paginated variant for the admin console list — see InvoiceService#findAllForAdmin. */
    Page<Invoice> findAllByOrderByIssuedAtDesc(Pageable pageable);

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    Optional<Invoice> findByPaymentIdAndType(Long paymentId, InvoiceType type);

    Optional<Invoice> findByRefundId(Long refundId);

    List<Invoice> findByBookingIdOrderByIssuedAtDesc(Long bookingId);

    @Query("select coalesce(max(i.id), 0) from Invoice i")
    Long findMaxId();
}
