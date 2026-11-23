package com.oorja.backend.service;

import com.oorja.backend.config.CompanyProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

/** The Resend provider talks to the HTTPS API instead of SMTP, and reports failures so OTP mail can surface them. */
class EmailServiceResendTest {

    HttpServer server;
    AtomicReference<String> body = new AtomicReference<>();
    AtomicReference<String> auth = new AtomicReference<>();
    volatile int status = 200;
    JavaMailSender smtp = Mockito.mock(JavaMailSender.class);

    @BeforeEach
    void startStubResend() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/emails", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = (status == 200 ? "{\"id\":\"abc\"}" : "{\"message\":\"domain not verified\"}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, resp.length);
            ex.getResponseBody().write(resp);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private EmailService service(String apiKey) {
        CompanyProperties company = Mockito.mock(CompanyProperties.class);
        Mockito.when(company.getName()).thenReturn("Oorja on Move");
        Mockito.when(company.getEmail()).thenReturn("support@oorja.test");
        EmailService s = new EmailService(smtp, company, new MockEnvironment());
        ReflectionTestUtils.setField(s, "fromAddress", "Oorja <noreply@oorja.test>");
        ReflectionTestUtils.setField(s, "mailEnabled", true);
        ReflectionTestUtils.setField(s, "mailProvider", "resend");
        ReflectionTestUtils.setField(s, "resendApiKey", apiKey);
        ReflectionTestUtils.setField(s, "resendApiUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/emails");
        return s;
    }

    @Test
    void otpIsPostedToResendWithBearerKeyAndNeverTouchesSmtp() {
        service("re_test_key").sendOtpEmail("rahul@example.com", "482915", "log in");

        assertThat(auth.get()).isEqualTo("Bearer re_test_key");
        assertThat(body.get())
                .contains("\"to\":[\"rahul@example.com\"]")
                .contains("\"from\":\"Oorja <noreply@oorja.test>\"")
                .contains("482915")
                .contains("\"content_id\":\"logo\"")
                .contains("\"reply_to\":\"support@oorja.test\"");
        verifyNoInteractions(smtp);
    }

    @Test
    void otpSendFailsLoudlyWhenResendRejectsTheMessage() {
        status = 403;
        assertThatThrownBy(() -> service("re_test_key").sendOtpEmail("rahul@example.com", "482915", "log in"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unable to send OTP email");
    }

    @Test
    void otpSendFailsLoudlyWhenApiKeyMissing() {
        assertThatThrownBy(() -> service("").sendOtpEmail("rahul@example.com", "482915", "log in"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Unable to send OTP email");
        assertThat(body.get()).isNull();
    }

    @Test
    void nonOtpMailFailureIsSwallowedAsBefore() {
        status = 500;
        service("re_test_key").sendSupportReplyToCustomer("a@x.com", "A", "T-1", "Subj", "hello", "admin");
        assertThat(body.get()).contains("T-1");
    }

    @Test
    void smtpRemainsTheDefaultProvider() {
        EmailService s = service("re_test_key");
        ReflectionTestUtils.setField(s, "mailProvider", "smtp");
        Mockito.when(smtp.createMimeMessage()).thenThrow(new IllegalStateException("smtp down"));

        assertThatThrownBy(() -> s.sendOtpEmail("a@x.com", "111111", "log in")).isInstanceOf(RuntimeException.class);
        assertThat(body.get()).isNull();
    }
}
