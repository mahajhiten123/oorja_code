package com.oorja.backend.dto.response;

import com.oorja.backend.entity.Technician;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TechnicianResponse {
    private Long id;
    private String technicianCode;
    private String name;
    private String phone;
    private String email;
    private String specialization;
    private String area;
    /** Derived: AVAILABLE, ON_JOB (has an active booking) or OFF_DUTY (admin-set). */
    private String status;
    /** Number of currently active (ASSIGNED / ON_SITE) bookings. */
    private long activeBookings;
    private String currentBookingToken;
    private String currentBookingStatus;
    private long jobsCompleted;

    public static TechnicianResponse from(Technician t) {
        return TechnicianResponse.builder()
                .id(t.getId())
                .technicianCode(t.getTechnicianCode())
                .name(t.getName())
                .phone(t.getPhone())
                .email(t.getEmail())
                .specialization(t.getSpecialization())
                .area(t.getArea())
                .build();
    }
}
