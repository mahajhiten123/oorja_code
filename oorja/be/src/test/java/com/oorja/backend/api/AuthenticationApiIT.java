package com.oorja.backend.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.oorja.backend.support.AbstractIT;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthenticationApiIT extends AbstractIT {

    /** Drives the real OTP flow: the plain code is read from the (mocked) outgoing email. */
    private String lastOtpSentTo(String email) {
        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendOtpEmail(eq(email), otp.capture(), any());
        return otp.getValue();
    }

    private JsonNode loginViaOtp(TestCustomer c) throws Exception {
        doPost("/api/customer/auth/login/send-otp", null, "{\"email\":\"" + c.email() + "\"}").andExpect(status().isOk());
        String code = lastOtpSentTo(c.email());
        return body(doPost("/api/customer/auth/login/verify-otp", null,
                "{\"email\":\"" + c.email() + "\",\"otp\":\"" + code + "\"}").andExpect(status().isOk()));
    }

    @Test
    void otpLoginIssuesWorkingAccessAndRefreshTokens() throws Exception {
        TestCustomer c = newCustomer();

        JsonNode auth = loginViaOtp(c);

        assertThat(auth.get("role").asText()).isEqualTo("CUSTOMER");
        assertThat(auth.get("refreshToken").asText()).isNotBlank();
        doGet("/api/customer/bookings", auth.get("token").asText()).andExpect(status().isOk());
    }

    @Test
    void wrongOtpIsRejectedAndFiveWrongAttemptsLockTheCode() throws Exception {
        TestCustomer c = newCustomer();
        doPost("/api/customer/auth/login/send-otp", null, "{\"email\":\"" + c.email() + "\"}").andExpect(status().isOk());
        String real = lastOtpSentTo(c.email());
        String wrong = real.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            doPost("/api/customer/auth/login/verify-otp", null,
                    "{\"email\":\"" + c.email() + "\",\"otp\":\"" + wrong + "\"}").andExpect(status().isBadRequest());
        }
        // locked: even the correct code no longer works
        doPost("/api/customer/auth/login/verify-otp", null,
                "{\"email\":\"" + c.email() + "\",\"otp\":\"" + real + "\"}").andExpect(status().isTooManyRequests());
    }

    @Test
    void otpCanOnlyBeUsedOnce() throws Exception {
        TestCustomer c = newCustomer();
        doPost("/api/customer/auth/login/send-otp", null, "{\"email\":\"" + c.email() + "\"}").andExpect(status().isOk());
        String code = lastOtpSentTo(c.email());
        String payload = "{\"email\":\"" + c.email() + "\",\"otp\":\"" + code + "\"}";

        doPost("/api/customer/auth/login/verify-otp", null, payload).andExpect(status().isOk());
        doPost("/api/customer/auth/login/verify-otp", null, payload).andExpect(status().isBadRequest());
    }

    @Test
    void sixthOtpRequestInTheWindowIsRateLimited() throws Exception {
        TestCustomer c = newCustomer();
        String payload = "{\"email\":\"" + c.email() + "\"}";
        for (int i = 0; i < 5; i++) {
            doPost("/api/customer/auth/login/send-otp", null, payload).andExpect(status().isOk());
        }
        doPost("/api/customer/auth/login/send-otp", null, payload).andExpect(status().isTooManyRequests());
    }

    @Test
    void otpForUnknownEmailIs404() throws Exception {
        doPost("/api/customer/auth/login/send-otp", null, "{\"email\":\"nobody-" + shortId() + "@test.io\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void registrationCreatesAnAccountThatCanLogIn() throws Exception {
        String email = "new-" + shortId() + "@Test.io";
        doPost("/api/customer/auth/register/send-otp", null,
                "{\"name\":\"New User\",\"email\":\"" + email + "\",\"phone\":\"9876543210\"}").andExpect(status().isOk());
        String code = lastOtpSentTo(email);

        JsonNode auth = body(doPost("/api/customer/auth/register/verify-otp", null,
                "{\"name\":\"New User\",\"email\":\"" + email + "\",\"phone\":\"9876543210\",\"address\":\"Pune\",\"otp\":\"" + code + "\"}")
                .andExpect(status().isOk()));

        assertThat(auth.get("email").asText()).isEqualTo(email.toLowerCase());
        assertThat(count("select count(*) from customers where lower(email)=?", email.toLowerCase())).isEqualTo(1);
        // registering the same email again is a conflict
        doPost("/api/customer/auth/register/send-otp", null,
                "{\"name\":\"New User\",\"email\":\"" + email.toUpperCase() + "\",\"phone\":\"9876543210\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void refreshRotatesAndReusingTheOldTokenRevokesTheWholeSession() throws Exception {
        TestCustomer c = newCustomer();
        JsonNode first = loginViaOtp(c);
        String r1 = first.get("refreshToken").asText();

        JsonNode second = body(doPost("/api/customer/auth/refresh", null, "{\"refreshToken\":\"" + r1 + "\"}").andExpect(status().isOk()));
        String r2 = second.get("refreshToken").asText();
        assertThat(r2).isNotEqualTo(r1);

        // replay of the consumed token: rejected, and it burns the legitimately-rotated token too
        doPost("/api/customer/auth/refresh", null, "{\"refreshToken\":\"" + r1 + "\"}").andExpect(status().isUnauthorized());
        doPost("/api/customer/auth/refresh", null, "{\"refreshToken\":\"" + r2 + "\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void garbageRefreshTokenIs401() throws Exception {
        doPost("/api/customer/auth/refresh", null, "{\"refreshToken\":\"not-a-real-token\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void logoutBlacklistsTheAccessTokenAndRevokesRefresh() throws Exception {
        TestCustomer c = newCustomer();
        JsonNode auth = loginViaOtp(c);
        String access = auth.get("token").asText();
        String refresh = auth.get("refreshToken").asText();
        doGet("/api/customer/bookings", access).andExpect(status().isOk());

        doPost("/api/customer/auth/logout", access, "{\"refreshToken\":\"" + refresh + "\"}").andExpect(status().isOk());

        doGet("/api/customer/bookings", access).andExpect(status().isUnauthorized());
        doPost("/api/customer/auth/refresh", null, "{\"refreshToken\":\"" + refresh + "\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void deactivatedCustomerCannotRequestOtpOrRefresh() throws Exception {
        TestCustomer c = newCustomer();
        JsonNode auth = loginViaOtp(c);
        jdbc.update("update customers set status='INACTIVE' where id=?", c.id());

        doPost("/api/customer/auth/login/send-otp", null, "{\"email\":\"" + c.email() + "\"}").andExpect(status().isUnauthorized());
        doPost("/api/customer/auth/refresh", null, "{\"refreshToken\":\"" + auth.get("refreshToken").asText() + "\"}")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminPasswordLoginWorksAndWrongPasswordIs401() throws Exception {
        JsonNode auth = body(doPost("/api/admin/auth/login", null, "{\"password\":\"Test-Only-Admin-Password-42\"}")
                .andExpect(status().isOk()));
        assertThat(auth.get("role").asText()).isEqualTo("SUPER_ADMIN");
        doGet("/api/admin/refunds/queue", auth.get("token").asText()).andExpect(status().isOk());

        doPost("/api/admin/auth/login", null, "{\"password\":\"wrong\"}").andExpect(status().isUnauthorized());
    }

    @Test
    void adminRefreshTokenCannotBeUsedOnTheCustomerEndpointAndViceVersa() throws Exception {
        JsonNode admin = body(doPost("/api/admin/auth/login", null, "{\"password\":\"Test-Only-Admin-Password-42\"}")
                .andExpect(status().isOk()));
        doPost("/api/customer/auth/refresh", null, "{\"refreshToken\":\"" + admin.get("refreshToken").asText() + "\"}")
                .andExpect(status().isUnauthorized());

        JsonNode cust = loginViaOtp(newCustomer());
        doPost("/api/admin/auth/refresh", null, "{\"refreshToken\":\"" + cust.get("refreshToken").asText() + "\"}")
                .andExpect(status().isUnauthorized());
    }
}
