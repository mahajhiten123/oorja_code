package com.oorja.backend.api;

import com.oorja.backend.support.AbstractIT;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Under the default (non-local) configuration the OTP must reach neither the API response nor the console. */
@ExtendWith(OutputCaptureExtension.class)
class OtpExposureApiIT extends AbstractIT {

    @Test
    void otpIsNotInTheResponseOrTheConsoleWithoutTheLocalProfile(CapturedOutput output) throws Exception {
        TestCustomer c = newCustomer();

        String response = doPost("/api/customer/auth/login/send-otp", null, "{\"email\":\"" + c.email() + "\"}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        ArgumentCaptor<String> otp = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendOtpEmail(eq(c.email()), otp.capture(), any());
        assertThat(response).doesNotContain(otp.getValue());
        assertThat(output.getAll()).doesNotContain(otp.getValue());
    }
}
