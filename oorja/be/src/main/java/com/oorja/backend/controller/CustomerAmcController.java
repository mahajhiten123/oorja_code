package com.oorja.backend.controller;

import com.oorja.backend.dto.request.AmcQuoteRequest;
import com.oorja.backend.dto.response.BookingResponse;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AMC (Annual Maintenance Contract) is presented to customers as
 * "Coming soon" - rather than taking payment for a plan that isn't live
 * yet, this lets a customer register interest against one of their plants.
 * It's priced and turned into a real, payable quotation through the exact
 * same workflow as an installation quote (Admin -> Quotations).
 */
@RestController
@RequestMapping("/api/customer/amc")
@RequiredArgsConstructor
public class CustomerAmcController {

    private final BookingService bookingService;

    @PostMapping("/request-quote")
    public ResponseEntity<BookingResponse> requestQuote(@Valid @RequestBody AmcQuoteRequest request) {
        BookingResponse response = bookingService.requestAmcQuote(
                CurrentUser.get().userId(), request.plantId(), request.planLabel(), request.notes());
        return ResponseEntity.ok(response);
    }
}
