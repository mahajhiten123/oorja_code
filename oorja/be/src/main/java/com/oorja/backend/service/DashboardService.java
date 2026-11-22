package com.oorja.backend.service;

import com.oorja.backend.dto.response.BookingResponse;
import com.oorja.backend.dto.response.DashboardSummaryResponse;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.repository.BookingRepository;
import com.oorja.backend.repository.CustomerRepository;
import com.oorja.backend.repository.PlantRepository;
import com.oorja.backend.repository.TechnicianRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final PlantRepository plantRepository;
    private final CustomerRepository customerRepository;
    private final TechnicianRepository technicianRepository;
    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public DashboardSummaryResponse getSummary() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (BookingStatus status : BookingStatus.values()) {
            byStatus.put(status.name(), bookingRepository.countByStatus(status));
        }

        return DashboardSummaryResponse.builder()
                .totalPlants(plantRepository.count())
                .totalCustomers(customerRepository.count())
                .totalTechnicians(technicianRepository.count())
                .totalBookings(bookingRepository.count())
                .bookingsThisWeek(bookingRepository.countCreatedSince(LocalDateTime.now().minusDays(7)))
                .bookingsByStatus(byStatus)
                .recentBookings(bookingRepository.findTop10ByOrderByCreatedAtDesc()
                        .stream().map(BookingResponse::from).toList())
                .build();
    }
}
