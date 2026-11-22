package com.oorja.backend.controller;

import com.oorja.backend.service.PaymentWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Not behind customer/admin JWT auth (see SecurityConfig — this path is
 * permitAll) since Razorpay itself is the caller. Authenticity is instead
 * established by verifying X-Razorpay-Signature against the raw body inside
 * PaymentWebhookService, exactly like the docs recommend.
 */
@RestController
@RequestMapping("/api/webhooks/razorpay")
@RequiredArgsConstructor
public class RazorpayWebhookController {

    private final PaymentWebhookService paymentWebhookService;

    @PostMapping
    public ResponseEntity<String> handle(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId
    ) {
        paymentWebhookService.process(rawBody, signature, eventId);
        return ResponseEntity.ok("ok");
    }
}
