package com.oorja.backend.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.oorja.backend.config.CompanyProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The OTP may reach the console only on an opted-in local machine, and never otherwise. */
class EmailServiceOtpLoggingTest {

    static final String OTP = "482915";
    static final String EMAIL = "rahul.sharma@example.com";

    ListAppender<ILoggingEvent> logs;
    Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(EmailService.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void release() {
        logger.detachAppender(logs);
    }

    private EmailService service(boolean mailEnabled, boolean logToConsole, String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        JavaMailSender sender = Mockito.mock(JavaMailSender.class);
        // enabled-mail path: sending fails fast; we only care that the OTP is not logged on the way
        Mockito.when(sender.createMimeMessage()).thenThrow(new IllegalStateException("smtp down"));
        EmailService s = new EmailService(sender, Mockito.mock(CompanyProperties.class), env);
        ReflectionTestUtils.setField(s, "fromAddress", "from@oorja.test");
        ReflectionTestUtils.setField(s, "mailEnabled", mailEnabled);
        ReflectionTestUtils.setField(s, "otpLogToConsole", logToConsole);
        return s;
    }

    private String allLogOutput() {
        StringBuilder sb = new StringBuilder();
        logs.list.forEach(e -> sb.append(e.getFormattedMessage()).append('\n'));
        return sb.toString();
    }

    @Test
    void localProfileWithFlagAndMailDisabledPrintsOtpAndMaskedRecipient() {
        service(false, true, "local").sendOtpEmail(EMAIL, OTP, "log in");

        String out = allLogOutput();
        assertThat(out).contains(OTP).contains("r***a@example.com");
        assertThat(out).doesNotContain("rahul.sharma");
    }

    @Test
    void flagDefaultsToOffSoLocalProfileAloneNeverPrintsOtp() {
        service(false, false, "local").sendOtpEmail(EMAIL, OTP, "log in");

        assertThat(allLogOutput()).doesNotContain(OTP);
    }

    @Test
    void flagWithoutLocalProfileNeverPrintsOtp() {
        service(false, true).sendOtpEmail(EMAIL, OTP, "log in");
        service(false, true, "dev").sendOtpEmail(EMAIL, OTP, "log in");

        assertThat(allLogOutput()).doesNotContain(OTP);
    }

    @Test
    void productionProfileNeverPrintsOtpEvenIfFlagAndMailSettingsAreWrong() {
        service(false, true, "prod").sendOtpEmail(EMAIL, OTP, "log in");
        service(false, true, "production").sendOtpEmail(EMAIL, OTP, "log in");
        service(false, true, "local", "prod").sendOtpEmail(EMAIL, OTP, "log in");

        assertThat(allLogOutput()).doesNotContain(OTP);
    }

    @Test
    void enabledMailNeverPrintsOtpEvenOnLocalWithFlag() {
        assertThatThrownBy(() -> service(true, true, "local").sendOtpEmail(EMAIL, OTP, "log in"))
                .isInstanceOf(RuntimeException.class);

        assertThat(allLogOutput()).doesNotContain(OTP);
    }

    @Test
    void maskingHidesMostOfTheAddress() {
        assertThat(EmailService.maskEmail("rahul.sharma@example.com")).isEqualTo("r***a@example.com");
        assertThat(EmailService.maskEmail("ab@x.io")).isEqualTo("a***@x.io");
        assertThat(EmailService.maskEmail(null)).isEqualTo("(no recipient)");
        assertThat(EmailService.maskEmail("nonsense")).isEqualTo("***");
    }
}
