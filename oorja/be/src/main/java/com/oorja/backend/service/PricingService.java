package com.oorja.backend.service;

import com.oorja.backend.exception.ApiException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The backend's own copy of the service catalog and pricing rules — this is
 * intentionally duplicated from the frontend's {@code data/services.ts}
 * rather than trusted from it. The frontend is free to display whatever it
 * wants; the amount a customer is actually charged always comes from here.
 * <p>
 * Keep this in sync with {@code admin/src/data/services.ts} whenever a
 * price or plan changes — that file is source of truth for what customers
 * *see*, this one is source of truth for what they *pay*.
 */
@Service
public class PricingService {

    private static final String VALID_COUPON = "OOM20";
    private static final double COUPON_DISCOUNT_PCT = 0.20;
    private static final double GST_PCT = 0.18;

    /** Base price in paise for services with a single flat price (no tiers). */
    private static final Map<String, Long> FLAT_PRICE_PAISE = Map.of(
            "installation", 6_000_000L,
            "expansion", 5_000_000L,
            "maintenance", 350_000L,
            "breakdown", 350_000L,
            "inspection", 200_000L
    );

    /** Cleaning is the only service with tiers today. */
    private static final Map<String, Long> CLEANING_TIER_PAISE = Map.of(
            "regular", 250_000L,
            "deep", 350_000L
    );
    private static final String DEFAULT_CLEANING_TIER = "regular";

    public record PriceQuote(
            long subtotalPaise,
            long discountPaise,
            long taxPaise,
            long totalPaise,
            String currency,
            boolean couponApplied,
            String couponError
    ) {}

    /**
     * @param serviceId    one of the ids in data/services.ts (installation, expansion, maintenance, breakdown, cleaning, inspection)
     * @param tierId       cleaning tier id ("regular"/"deep"); ignored for other services
     * @param couponCode   optional promo code, case-insensitive
     * @param quoteRequest true for "request a quote" flows (installation/expansion/breakdown site surveys) — these are priced later by an admin, not charged now
     */
    public PriceQuote quote(String serviceId, String tierId, String couponCode, boolean quoteRequest) {
        long subtotal = basePriceFor(serviceId, tierId);

        if (quoteRequest) {
            // Quote requests don't collect payment now — a formal, admin-priced
            // quotation is sent later. Nothing here is actually charged, but we
            // still return a shape the frontend can display consistently.
            return new PriceQuote(subtotal, 0, 0, subtotal, "INR", false, null);
        }

        boolean couponApplied = false;
        String couponError = null;
        long discount = 0;
        if (couponCode != null && !couponCode.isBlank()) {
            if (VALID_COUPON.equalsIgnoreCase(couponCode.trim())) {
                couponApplied = true;
                discount = Math.round(subtotal * COUPON_DISCOUNT_PCT);
            } else {
                couponError = "Invalid or expired coupon code.";
            }
        }

        long discountedSubtotal = subtotal - discount;
        long tax = Math.round(discountedSubtotal * GST_PCT);
        long total = discountedSubtotal + tax;

        return new PriceQuote(subtotal, discount, tax, total, "INR", couponApplied, couponError);
    }

    private long basePriceFor(String serviceId, String tierId) {
        if (serviceId == null) {
            throw ApiException.badRequest("A service must be selected before pricing can be calculated.");
        }
        if ("cleaning".equals(serviceId)) {
            String tier = (tierId == null || tierId.isBlank()) ? DEFAULT_CLEANING_TIER : tierId;
            Long price = CLEANING_TIER_PAISE.get(tier);
            if (price == null) {
                throw ApiException.badRequest("Unknown cleaning tier: " + tierId);
            }
            return price;
        }
        Long price = FLAT_PRICE_PAISE.get(serviceId);
        if (price == null) {
            throw ApiException.badRequest("Unknown service: " + serviceId);
        }
        return price;
    }
}
