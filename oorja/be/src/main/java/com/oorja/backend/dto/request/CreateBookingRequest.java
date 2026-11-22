package com.oorja.backend.dto.request;

import jakarta.validation.constraints.*;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalTime;

public record CreateBookingRequest(
        /** Optional - if omitted, the customer's first registered plant is used
         *  (or a starter plant is auto-created from {@code location} for
         *  brand-new customers who haven't registered one yet). */
        Long plantId,
        @NotBlank @Size(max = 150) String serviceType,
        @NotNull @FutureOrPresent LocalDate scheduledDate,
        @NotNull LocalTime scheduledTime,
        @Size(max = 2000) String notes,
        @Size(max = 255) String location,
        /**
         * The Razorpay order id from a completed, verified payment (see
         * POST /api/customer/payments/create-order + /verify). If present,
         * the booking's amount/payment reference are copied server-side
         * from that CAPTURED payment — the client can no longer just tell
         * us what it paid. Omit entirely for quote requests / free bookings.
         */
        @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_]*$", message = "must contain only letters, digits and underscores") String razorpayOrderId,
        @Size(max = 200) String serviceLabel,
        /**
         * True for "request a quote" submissions (e.g. new installations) -
         * these skip payment and automatically get a draft {@code Quotation}
         * the admin can price.
         */
        Boolean requestQuote
) {}
