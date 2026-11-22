package com.oorja.backend.repository;

import com.oorja.backend.entity.Quotation;
import com.oorja.backend.entity.enums.QuotationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {

    Optional<Quotation> findByBookingId(Long bookingId);

    /**
     * Row-locking read used by QuotationService#acceptByCustomer (and
     * anywhere else acting on a quotation's response status) so two
     * concurrent accept/reject calls on the same quotation can't both
     * pass the RESPONDABLE status guard.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from Quotation q where q.id = :id")
    Optional<Quotation> findByIdForUpdate(@Param("id") Long id);

    List<Quotation> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    List<Quotation> findAllByOrderByCreatedAtDesc();

    List<Quotation> findByStatusInAndValidUntilBefore(List<QuotationStatus> statuses, LocalDate date);

    @Query("select coalesce(max(q.id), 0) from Quotation q")
    Long findMaxId();
}
