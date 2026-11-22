package com.oorja.backend.service;

import com.oorja.backend.exception.ApiException;
import com.oorja.backend.service.PricingService.PriceQuote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The amount a customer is charged comes from here and nowhere else, so every rule is pinned down. */
class PricingServiceTest {

    private final PricingService pricing = new PricingService();

    @ParameterizedTest(name = "{0} => subtotal {1}, GST {2}, total {3} (paise)")
    @CsvSource({
            "installation, 6000000, 1080000, 7080000",
            "expansion,    5000000,  900000, 5900000",
            "maintenance,   350000,   63000,  413000",
            "breakdown,     350000,   63000,  413000",
            "inspection,    200000,   36000,  236000"
    })
    void flatPricedServicesAddEighteenPercentGst(String service, long subtotal, long tax, long total) {
        PriceQuote q = pricing.quote(service, null, null, false);
        assertEquals(subtotal, q.subtotalPaise());
        assertEquals(0, q.discountPaise());
        assertEquals(tax, q.taxPaise());
        assertEquals(total, q.totalPaise());
        assertEquals("INR", q.currency());
    }

    @Test
    void cleaningDefaultsToTheRegularTier() {
        PriceQuote q = pricing.quote("cleaning", null, null, false);
        assertEquals(250_000, q.subtotalPaise());
        assertEquals(295_000, q.totalPaise());
        assertEquals(pricing.quote("cleaning", "regular", null, false).totalPaise(), q.totalPaise());
        assertEquals(q.totalPaise(), pricing.quote("cleaning", "  ", null, false).totalPaise(), "blank tier means default");
    }

    @Test
    void deepCleaningCostsMoreThanRegular() {
        PriceQuote deep = pricing.quote("cleaning", "deep", null, false);
        assertEquals(350_000, deep.subtotalPaise());
        assertEquals(63_000, deep.taxPaise());
        assertEquals(413_000, deep.totalPaise());
    }

    @Test
    void theTierIsIgnoredForServicesWithoutTiers() {
        assertEquals(pricing.quote("inspection", null, null, false).totalPaise(),
                pricing.quote("inspection", "deep", null, false).totalPaise());
    }

    @Test
    void unknownCleaningTierIsRejected() {
        ApiException e = assertThrows(ApiException.class, () -> pricing.quote("cleaning", "platinum", null, false));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void unknownServiceIsRejected() {
        ApiException e = assertThrows(ApiException.class, () -> pricing.quote("teleportation", null, null, false));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void missingServiceIsRejected() {
        ApiException e = assertThrows(ApiException.class, () -> pricing.quote(null, null, null, false));
        assertEquals(400, e.getStatus().value());
    }

    @ParameterizedTest
    @CsvSource({"OOM20", "oom20", "Oom20", "' OOM20 '"})
    void couponTakesTwentyPercentOffBeforeGstAndIsCaseInsensitive(String code) {
        PriceQuote q = pricing.quote("cleaning", "regular", code, false);
        assertTrue(q.couponApplied());
        assertNull(q.couponError());
        assertEquals(250_000, q.subtotalPaise());
        assertEquals(50_000, q.discountPaise());
        assertEquals(36_000, q.taxPaise(), "GST is charged on the discounted subtotal");
        assertEquals(236_000, q.totalPaise());
    }

    @Test
    void anInvalidCouponChangesNothingButReportsWhy() {
        PriceQuote plain = pricing.quote("cleaning", "regular", null, false);
        PriceQuote q = pricing.quote("cleaning", "regular", "FREEMONEY", false);
        assertFalse(q.couponApplied());
        assertEquals("Invalid or expired coupon code.", q.couponError());
        assertEquals(0, q.discountPaise());
        assertEquals(plain.totalPaise(), q.totalPaise());
    }

    @Test
    void aBlankCouponIsTreatedAsNoCoupon() {
        PriceQuote q = pricing.quote("cleaning", "regular", "   ", false);
        assertFalse(q.couponApplied());
        assertNull(q.couponError());
    }

    @Test
    void quoteRequestsShowTheBasePriceButChargeNothingExtra() {
        PriceQuote q = pricing.quote("installation", null, "OOM20", true);
        assertEquals(6_000_000, q.subtotalPaise());
        assertEquals(0, q.discountPaise(), "no coupon or tax on a quote request");
        assertEquals(0, q.taxPaise());
        assertEquals(6_000_000, q.totalPaise());
        assertFalse(q.couponApplied());
    }

    @Test
    void totalAlwaysEqualsSubtotalMinusDiscountPlusTax() {
        for (String service : new String[]{"installation", "expansion", "maintenance", "breakdown", "inspection", "cleaning"}) {
            for (String coupon : new String[]{null, "OOM20", "nope"}) {
                PriceQuote q = pricing.quote(service, null, coupon, false);
                assertEquals(q.subtotalPaise() - q.discountPaise() + q.taxPaise(), q.totalPaise(), service + "/" + coupon);
            }
        }
    }
}
