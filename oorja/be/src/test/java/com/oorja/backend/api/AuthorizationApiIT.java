package com.oorja.backend.api;

import com.oorja.backend.support.AbstractIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may call what, enforced by the real security filter chain. */
class AuthorizationApiIT extends AbstractIT {

    @ParameterizedTest
    @ValueSource(strings = {"/api/customer/bookings", "/api/customer/refunds", "/api/admin/refunds/queue", "/api/admin/bookings/1"})
    void protectedEndpointsRejectAnonymousCallers(String url) throws Exception {
        doGet(url, null).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/admin/refunds/queue", "/api/admin/refunds", "/api/admin/refunds/reconciliation", "/api/admin/bookings/1"})
    void customersCannotReachAdminEndpoints(String url) throws Exception {
        doGet(url, tokenFor(newCustomer())).andExpect(status().isForbidden());
    }

    @Test
    void adminTokenCannotCallCustomerEndpoints() throws Exception {
        doGet("/api/customer/bookings", adminToken()).andExpect(status().isForbidden());
    }

    @Test
    void customerCannotApproveRejectOrRetryRefundsOrCancelAsAdmin() throws Exception {
        String t = tokenFor(newCustomer());
        doPost("/api/admin/refunds/1/approve", t, null).andExpect(status().isForbidden());
        doPost("/api/admin/refunds/1/reject", t, null).andExpect(status().isForbidden());
        doPost("/api/admin/refunds/1/retry", t, null).andExpect(status().isForbidden());
        doPost("/api/admin/bookings/1/cancel", t, null).andExpect(status().isForbidden());
        doPost("/api/admin/bookings/1/complete", t, null).andExpect(status().isForbidden());
    }

    @Test
    void malformedAndTamperedTokensAreRejected() throws Exception {
        String good = tokenFor(newCustomer());
        doGet("/api/customer/bookings", "garbage").andExpect(status().isUnauthorized());
        doGet("/api/customer/bookings", good.substring(0, good.length() - 4) + "XXXX").andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithADifferentSecretIsRejected() throws Exception {
        com.oorja.backend.config.JwtService other = new com.oorja.backend.config.JwtService();
        org.springframework.test.util.ReflectionTestUtils.setField(other, "secret", "a-completely-different-secret-0123456789-abcdefghijk");
        org.springframework.test.util.ReflectionTestUtils.setField(other, "expirationMs", 60_000L);
        String forged = other.generateToken("admin@test.io", 1L, "SUPER_ADMIN");

        doGet("/api/admin/refunds/queue", forged).andExpect(status().isUnauthorized());
    }

    @Test
    void slotAvailabilityIsPublicButBookingsAreNot() throws Exception {
        doGet("/api/customer/bookings/slots?date=" + freshDay(), null).andExpect(status().isOk());
        doGet("/api/customer/bookings", null).andExpect(status().isUnauthorized());
    }

    @Test
    void webhookEndpointNeedsNoBearerTokenButNeedsAValidSignature() throws Exception {
        org.mockito.Mockito.when(razorpay.verifyWebhookSignature(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(false);
        webhook("{\"event\":\"payment.captured\"}", "evt_" + shortId()).andExpect(status().isUnauthorized());
    }
}
