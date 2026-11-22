package com.oorja.backend.service;

import com.oorja.backend.dto.response.AdminMemberResponse;
import com.oorja.backend.dto.response.BookingResponse;
import com.oorja.backend.dto.response.MemberBookingsResponse;
import com.oorja.backend.dto.response.PlantResponse;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.CustomerRepository;
import com.oorja.backend.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read model for the admin "Members" screen: each registered customer,
 * the site(s)/plant(s) they've registered, and their bookings split into
 * upcoming / completed / cancelled. AMC subscription data isn't wired up
 * yet — that lands once real AMC data is provided.
 */
@Service
@RequiredArgsConstructor
public class AdminMemberService {

    private static final List<BookingStatus> UPCOMING_STATUSES =
            List.of(BookingStatus.PENDING, BookingStatus.ASSIGNED, BookingStatus.ON_SITE);

    private final CustomerRepository customerRepository;
    private final PlantRepository plantRepository;
    private final BookingService bookingService;

    /** Paginated for the admin console list — an earlier unpaginated version loaded the whole customer table into memory. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<AdminMemberResponse> findAll(org.springframework.data.domain.Pageable pageable) {
        return customerRepository.findAll(pageable).map(this::enrich);
    }

    @Transactional(readOnly = true)
    public AdminMemberResponse findById(Long id) {
        Customer customer = customerRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Customer not found: " + id));
        return enrich(customer);
    }

    private AdminMemberResponse enrich(Customer customer) {
        List<PlantResponse> sites = plantRepository.findByCustomerId(customer.getId())
                .stream().map(PlantResponse::from).toList();

        List<BookingResponse> allBookings = bookingService.findByCustomer(customer.getId());

        List<BookingResponse> upcoming = allBookings.stream()
                .filter(b -> UPCOMING_STATUSES.contains(BookingStatus.valueOf(b.getStatus())))
                .toList();
        List<BookingResponse> completed = allBookings.stream()
                .filter(b -> BookingStatus.valueOf(b.getStatus()) == BookingStatus.COMPLETED)
                .toList();
        List<BookingResponse> cancelled = allBookings.stream()
                .filter(b -> BookingStatus.valueOf(b.getStatus()) == BookingStatus.CANCELLED)
                .toList();

        MemberBookingsResponse bookings = MemberBookingsResponse.builder()
                .upcomingCount(upcoming.size())
                .completedCount(completed.size())
                .cancelledCount(cancelled.size())
                .upcoming(upcoming)
                .completed(completed)
                .cancelled(cancelled)
                .build();

        return AdminMemberResponse.from(customer, sites, bookings);
    }
}
