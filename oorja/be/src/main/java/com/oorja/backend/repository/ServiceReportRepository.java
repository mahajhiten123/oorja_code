package com.oorja.backend.repository;

import com.oorja.backend.entity.ServiceReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ServiceReportRepository extends JpaRepository<ServiceReport, Long> {

    Optional<ServiceReport> findByBookingId(Long bookingId);

    List<ServiceReport> findByCustomerIdAndPublishedTrueOrderByServiceDateDesc(Long customerId);

    List<ServiceReport> findAllByOrderByServiceDateDesc();

    @Query("select coalesce(max(r.id), 0) from ServiceReport r")
    Long findMaxId();
}
