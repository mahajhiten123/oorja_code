package com.oorja.backend.repository;

import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.enums.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByToken(String token);

    Optional<Booking> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);

    /**
     * Row-locking read used by every state-changing booking action (cancel,
     * complete, reschedule, assign, admin edits) so two concurrent requests
     * against the same booking can't both read the pre-change status and
     * both commit — see BookingService#getOrThrowForUpdate.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    List<Booking> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<Booking> findByTechnicianIdAndStatusIn(Long technicianId, List<BookingStatus> statuses);
    long countByStatus(BookingStatus status);

    @Query("select count(b) from Booking b where b.createdAt >= :since")
    long countCreatedSince(LocalDateTime since);

    @Query("select coalesce(max(b.id), 0) from Booking b")
    Long findMaxId();

    List<Booking> findTop10ByOrderByCreatedAtDesc();
}
