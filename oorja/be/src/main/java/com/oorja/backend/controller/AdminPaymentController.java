package com.oorja.backend.controller;

import com.oorja.backend.dto.response.AdminPaymentResponse;
import com.oorja.backend.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

/**
 * Admin console -> Payments. Read-only view over the payment ledger
 * (Payment + PaymentTransaction) built by PaymentService — every Razorpay
 * order ever created, whether or not it was ever paid, plus its full
 * audit trail. Refund actions live under AdminRefundController.
 */
@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public Page<AdminPaymentResponse> getAll(@PageableDefault(size = 20) Pageable pageable) {
        return paymentService.findAllForAdmin(pageable);
    }

    @GetMapping("/{id}")
    public AdminPaymentResponse getOne(@PathVariable Long id) {
        return paymentService.findByIdForAdmin(id);
    }

    /** Used by the booking-detail view to show what actually funded a booking. */
    @GetMapping("/by-booking/{bookingId}")
    public AdminPaymentResponse getByBooking(@PathVariable Long bookingId) {
        return paymentService.findByBookingIdForAdmin(bookingId);
    }
}
