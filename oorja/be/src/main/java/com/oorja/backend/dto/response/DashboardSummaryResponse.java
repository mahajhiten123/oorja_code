package com.oorja.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardSummaryResponse {
    private long totalPlants;
    private long totalCustomers;
    private long totalTechnicians;
    private long totalBookings;
    private long bookingsThisWeek;
    private Map<String, Long> bookingsByStatus;
    private List<BookingResponse> recentBookings;
}
