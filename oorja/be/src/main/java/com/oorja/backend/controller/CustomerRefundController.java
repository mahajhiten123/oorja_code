package com.oorja.backend.controller;

import com.oorja.backend.dto.request.RebookFromRefundRequest;
import com.oorja.backend.dto.response.BookingResponse;
import com.oorja.backend.dto.response.RefundResponse;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.BookingService;
import com.oorja.backend.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** The customer's own view of money coming back to them — and the way out of it. */
@RestController
@RequestMapping("/api/customer/refunds")
@RequiredArgsConstructor
public class CustomerRefundController {

    private final RefundService refundService;
    private final BookingService bookingService;

    @GetMapping
    public List<RefundResponse> myRefunds() {
        return refundService.findByCustomer(CurrentUser.get().userId());
    }

    /** One refund with its full timeline — what the tracking screen reads. */
    @GetMapping("/{id}")
    public RefundResponse one(@PathVariable Long id) {
        return refundService.findOneForCustomer(id, CurrentUser.get().userId());
    }

    /**
     * "Don't refund me — book me in for this slot instead."
     *
     * Withdraws the pending refund and revives the booking at the new time,
     * with the original payment intact, so the customer keeps the full value
     * rather than losing a policy deduction and paying again.
     */
    @PostMapping("/{id}/rebook")
    public BookingResponse rebook(@PathVariable Long id, @Valid @RequestBody RebookFromRefundRequest request) {
        return bookingService.rebookFromRefund(CurrentUser.get().userId(), id,
                request.scheduledDate(), request.scheduledTime());
    }
}
