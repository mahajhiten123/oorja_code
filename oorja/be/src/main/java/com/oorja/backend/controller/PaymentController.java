package com.oorja.backend.controller;

import com.oorja.backend.dto.request.CreateOrderForQuotationRequest;
import com.oorja.backend.dto.request.CreateOrderRequest;
import com.oorja.backend.dto.request.VerifyPaymentRequest;
import com.oorja.backend.dto.response.CreateOrderResponse;
import com.oorja.backend.dto.response.VerifyPaymentResponse;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customer/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /** Step 1: price the request server-side and open a Razorpay order for that exact amount. */
    @PostMapping("/create-order")
    public CreateOrderResponse createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        Long customerId = CurrentUser.get().userId();
        String key = IdempotencyKeys.normalize(idempotencyKey);
        try {
            return paymentService.createOrder(customerId, request, key);
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate lost on uq_payments_customer_idem: return the winner's order.
            return paymentService.findOrderByIdempotencyKey(customerId, key).orElseThrow(() -> e);
        }
    }

    /** Same as create-order, but for paying an already-priced Quotation (AMC/installation) instead of a catalog service. */
    @PostMapping("/create-order-for-quotation")
    public CreateOrderResponse createOrderForQuotation(
            @Valid @RequestBody CreateOrderForQuotationRequest request,
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        Long customerId = CurrentUser.get().userId();
        String key = IdempotencyKeys.normalize(idempotencyKey);
        try {
            return paymentService.createOrderForQuotation(customerId, request, key);
        } catch (DataIntegrityViolationException e) {
            return paymentService.findOrderByIdempotencyKey(customerId, key).orElseThrow(() -> e);
        }
    }

    /** Step 2: after Razorpay Checkout succeeds client-side, verify the signature here. */
    @PostMapping("/verify")
    public VerifyPaymentResponse verify(@Valid @RequestBody VerifyPaymentRequest request) {
        return paymentService.verify(CurrentUser.get().userId(), request);
    }
}
