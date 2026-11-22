package com.oorja.backend.service;

import com.oorja.backend.dto.request.TechnicianRequest;
import com.oorja.backend.dto.response.TechnicianResponse;
import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.Technician;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.entity.enums.TechnicianAvailability;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingRepository;
import com.oorja.backend.repository.TechnicianRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TechnicianService {

    private final TechnicianRepository technicianRepository;
    private final BookingRepository bookingRepository;
    private final CodeGeneratorService codeGeneratorService;

    private static final List<BookingStatus> ACTIVE_STATUSES = List.of(BookingStatus.ASSIGNED, BookingStatus.ON_SITE);

    @Transactional(readOnly = true)
    public List<TechnicianResponse> findAll() {
        return technicianRepository.findAll().stream().map(this::enrich).toList();
    }

    @Transactional(readOnly = true)
    public TechnicianResponse findById(Long id) {
        return enrich(getOrThrow(id));
    }

    @Transactional
    public TechnicianResponse create(TechnicianRequest request) {
        Technician technician = Technician.builder()
                .technicianCode(codeGeneratorService.nextTechnicianCode())
                .name(request.name())
                .phone(request.phone())
                .email(request.email())
                .specialization(request.specialization())
                .area(request.area() != null && !request.area().isBlank() ? request.area() : "Unassigned")
                .availability(TechnicianAvailability.AVAILABLE)
                .build();

        return enrich(technicianRepository.save(technician));
    }

    @Transactional
    public TechnicianResponse update(Long id, TechnicianRequest request) {
        Technician technician = getOrThrow(id);
        technician.setName(request.name());
        technician.setPhone(request.phone());
        technician.setEmail(request.email());
        technician.setSpecialization(request.specialization());
        if (request.area() != null && !request.area().isBlank()) {
            technician.setArea(request.area());
        }
        return enrich(technicianRepository.save(technician));
    }

    /** Admin toggles a technician on/off duty. "ON_JOB" can't be set directly — it's derived from active bookings. */
    @Transactional
    public TechnicianResponse setStatus(Long id, String status) {
        Technician technician = getOrThrow(id);
        TechnicianAvailability availability;
        try {
            availability = TechnicianAvailability.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(
                    "Status must be AVAILABLE or OFF_DUTY. \"ON_JOB\" is set automatically based on active bookings.");
        }
        technician.setAvailability(availability);
        return enrich(technicianRepository.save(technician));
    }

    @Transactional
    public void delete(Long id) {
        technicianRepository.delete(getOrThrow(id));
    }

    private Technician getOrThrow(Long id) {
        return technicianRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Technician not found: " + id));
    }

    private TechnicianResponse enrich(Technician technician) {
        TechnicianResponse response = TechnicianResponse.from(technician);

        List<Booking> activeBookings = bookingRepository.findByTechnicianIdAndStatusIn(
                technician.getId(), ACTIVE_STATUSES);

        long completed = bookingRepository.findByTechnicianIdAndStatusIn(
                technician.getId(), List.of(BookingStatus.COMPLETED)).size();

        response.setJobsCompleted(completed);
        response.setActiveBookings(activeBookings.size());

        if (!activeBookings.isEmpty()) {
            Booking b = activeBookings.get(0);
            response.setCurrentBookingToken(b.getToken());
            response.setCurrentBookingStatus(b.getStatus().name());
            response.setStatus("ON_JOB");
        } else {
            response.setStatus(technician.getAvailability().name());
        }

        return response;
    }
}
