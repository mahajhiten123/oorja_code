package com.oorja.backend.service;

import com.oorja.backend.entity.OtpToken;
import com.oorja.backend.entity.enums.OtpChannel;
import com.oorja.backend.entity.enums.OtpPurpose;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.OtpTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock OtpTokenRepository repo;
    @Mock EmailService emailService;
    OtpService service;

    @BeforeEach
    void setUp() {
        service = new OtpService(repo, emailService);
        ReflectionTestUtils.setField(service, "expiryMinutes", 5);
        ReflectionTestUtils.setField(service, "maxRequestsPerWindow", 5);
        ReflectionTestUtils.setField(service, "windowMinutes", 15);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
    }

    private OtpToken stored(String otp, int attempts, LocalDateTime expiresAt) {
        return OtpToken.builder().id(1L).identifier("a@x.com").otpHash(BCrypt.hashpw(otp, BCrypt.gensalt(4)))
                .purpose(OtpPurpose.CUSTOMER_LOGIN).channel(OtpChannel.EMAIL).attempts(attempts).used(false)
                .expiresAt(expiresAt).build();
    }

    private void whenTokenFound(OtpToken t) {
        when(repo.findTopByIdentifierIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDescForUpdate(anyString(), any()))
                .thenReturn(List.of(t));
    }

    @Test
    void generateStoresOnlyAHashAndEmailsThePlainCode() {
        when(repo.countRecentRequests(anyString(), any(), any())).thenReturn(0L);

        service.generateAndSend("A@X.com", OtpPurpose.CUSTOMER_LOGIN, "log in");

        ArgumentCaptor<OtpToken> saved = ArgumentCaptor.forClass(OtpToken.class);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(repo).save(saved.capture());
        verify(emailService).sendOtpEmail(eq("A@X.com"), sent.capture(), eq("log in"));

        assertThat(sent.getValue()).matches("\\d{6}");
        assertThat(saved.getValue().getOtpHash()).isNotEqualTo(sent.getValue());
        assertThat(BCrypt.checkpw(sent.getValue(), saved.getValue().getOtpHash())).isTrue();
        assertThat(saved.getValue().getIdentifier()).isEqualTo("a@x.com");
        assertThat(saved.getValue().isUsed()).isFalse();
        assertThat(saved.getValue().getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(4));
    }

    @Test
    void sixthRequestInWindowIsRateLimited() {
        when(repo.countRecentRequests(anyString(), any(), any())).thenReturn(5L);

        assertThatThrownBy(() -> service.generateAndSend("a@x.com", OtpPurpose.CUSTOMER_LOGIN, "x"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(repo, never()).save(any());
        verify(emailService, never()).sendOtpEmail(any(), any(), any());
    }

    @Test
    void correctCodeMarksTokenUsed() {
        OtpToken t = stored("123456", 0, LocalDateTime.now().plusMinutes(3));
        whenTokenFound(t);

        service.verify("a@x.com", "123456", OtpPurpose.CUSTOMER_LOGIN);

        assertThat(t.isUsed()).isTrue();
        verify(repo).save(t);
    }

    @Test
    void wrongCodeIncrementsAttemptsAndFails() {
        OtpToken t = stored("123456", 0, LocalDateTime.now().plusMinutes(3));
        whenTokenFound(t);

        assertThatThrownBy(() -> service.verify("a@x.com", "000000", OtpPurpose.CUSTOMER_LOGIN))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repo).incrementAttempts(1L);
        assertThat(t.isUsed()).isFalse();
    }

    @Test
    void exhaustedAttemptsAreLockedEvenForTheCorrectCode() {
        OtpToken t = stored("123456", 5, LocalDateTime.now().plusMinutes(3));
        whenTokenFound(t);

        assertThatThrownBy(() -> service.verify("a@x.com", "123456", OtpPurpose.CUSTOMER_LOGIN))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(t.isUsed()).isFalse();
    }

    @Test
    void expiredCodeIsRejected() {
        OtpToken t = stored("123456", 0, LocalDateTime.now().minusMinutes(1));
        whenTokenFound(t);

        assertThatThrownBy(() -> service.verify("a@x.com", "123456", OtpPurpose.CUSTOMER_LOGIN))
                .isInstanceOf(ApiException.class).hasMessageContaining("expired");
        assertThat(t.isUsed()).isFalse();
    }

    @Test
    void noOutstandingCodeIsRejected() {
        when(repo.findTopByIdentifierIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDescForUpdate(anyString(), any()))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.verify("a@x.com", "123456", OtpPurpose.CUSTOMER_LOGIN))
                .isInstanceOf(ApiException.class).hasMessageContaining("No OTP request");
    }

    @Test
    void bypassCodeAllowsVerificationWhenConfigured() {
        ReflectionTestUtils.setField(service, "bypassCode", "999999");
        OtpToken t = stored("123456", 0, LocalDateTime.now().plusMinutes(3));
        whenTokenFound(t);

        service.verify("a@x.com", "999999", OtpPurpose.CUSTOMER_LOGIN);

        assertThat(t.isUsed()).isTrue();
        verify(repo).save(t);
    }
}
