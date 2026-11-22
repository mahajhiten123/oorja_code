package com.oorja.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MemberBookingsResponse {
    private long upcomingCount;
    private long completedCount;
    private long cancelledCount;
    private List<BookingResponse> upcoming;
    private List<BookingResponse> completed;
    private List<BookingResponse> cancelled;
}
