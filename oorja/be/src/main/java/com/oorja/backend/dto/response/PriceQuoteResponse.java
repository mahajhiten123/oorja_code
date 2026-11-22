package com.oorja.backend.dto.response;

import com.oorja.backend.service.PricingService;
import lombok.Builder;

@Builder
public record PriceQuoteResponse(
        double subtotal,
        double discount,
        double tax,
        double total,
        String currency,
        boolean couponApplied,
        String couponError
) {
    public static PriceQuoteResponse from(PricingService.PriceQuote q) {
        return PriceQuoteResponse.builder()
                .subtotal(q.subtotalPaise() / 100.0)
                .discount(q.discountPaise() / 100.0)
                .tax(q.taxPaise() / 100.0)
                .total(q.totalPaise() / 100.0)
                .currency(q.currency())
                .couponApplied(q.couponApplied())
                .couponError(q.couponError())
                .build();
    }
}
