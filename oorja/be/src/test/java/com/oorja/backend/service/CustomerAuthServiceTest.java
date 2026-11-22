package com.oorja.backend.service;

import com.oorja.backend.config.JwtService;
import com.oorja.backend.dto.request.CustomerRegisterRequest;
import com.oorja.backend.dto.response.AuthResponse;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.enums.AccountStatus;
import com.oorja.backend.entity.enums.OtpPurpose;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerAuthServiceTest {

    @Mock CustomerRepository customers;
    @Mock OtpService otp;
    @Mock JwtService jwt;
    @Mock CodeGeneratorService codes;
    @Mock GoogleAuthService google;
    @Mock RefreshTokenService refresh;
    @Mock TokenBlacklistService blacklist;
    @Mock PlatformTransactionManager txm;

    CustomerAuthService service;

    @BeforeEach
    void setUp() {
        lenient().when(txm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new CustomerAuthService(customers, otp, jwt, codes, google, refresh, blacklist, new TransactionTemplate(txm));
        ReflectionTestUtils.setField(service, "accessTokenExpirationMs", 900_000L);
        lenient().when(jwt.generateToken(anyString(), any(), anyString())).thenReturn("access");
        lenient().when(refresh.issue(any(), any(), anyString(), anyString())).thenReturn("refresh");
        lenient().when(customers.save(any(Customer.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Customer active() {
        return Customer.builder().id(3L).name("N").email("a@x.com").phone("999999999").address("Addr")
                .status(AccountStatus.ACTIVE).build();
    }

    @Test
    void loginOtpForUnknownEmailIs404() {
        when(customers.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestLoginOtp("a@x.com"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        verify(otp, never()).generateAndSend(any(), any(), any());
    }

    @Test
    void loginOtpForDeactivatedAccountIs401() {
        Customer c = active();
        c.setStatus(AccountStatus.INACTIVE);
        when(customers.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.requestLoginOtp("a@x.com"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(401));
    }

    @Test
    void loginOtpSendsCodeToStoredEmail() {
        when(customers.findByEmailIgnoreCase("A@X.com")).thenReturn(Optional.of(active()));
        service.requestLoginOtp("A@X.com");
        verify(otp).generateAndSend(eq("a@x.com"), eq(OtpPurpose.CUSTOMER_LOGIN), anyString());
    }

    @Test
    void verifyLoginOtpIssuesTokenPair() {
        when(customers.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(active()));

        AuthResponse r = service.verifyLoginOtp("a@x.com", "123456");

        verify(otp).verify("a@x.com", "123456", OtpPurpose.CUSTOMER_LOGIN);
        assertThat(r.getToken()).isEqualTo("access");
        assertThat(r.getRefreshToken()).isEqualTo("refresh");
        assertThat(r.getRole()).isEqualTo("CUSTOMER");
        assertThat(r.getExpiresIn()).isEqualTo(900L);
        assertThat(r.isNeedsProfile()).isFalse();
    }

    @Test
    void wrongOtpIssuesNothing() {
        when(customers.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(active()));
        org.mockito.Mockito.doThrow(ApiException.badRequest("Incorrect OTP")).when(otp).verify(any(), any(), any());

        assertThatThrownBy(() -> service.verifyLoginOtp("a@x.com", "000000")).isInstanceOf(ApiException.class);
        verify(refresh, never()).issue(any(), any(), any(), any());
    }

    @Test
    void registerOtpForExistingEmailIsConflict() {
        when(customers.existsByEmailIgnoreCase("a@x.com")).thenReturn(true);
        assertThatThrownBy(() -> service.requestRegisterOtp(new CustomerRegisterRequest("N", "a@x.com", "9999999", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(409));
    }

    @Test
    void verifyRegisterCreatesLowercasedAccountAndFlagsMissingProfile() {
        when(customers.existsByEmailIgnoreCase("A@X.com")).thenReturn(false);
        when(codes.nextCustomerCode()).thenReturn("C-1");

        AuthResponse r = service.verifyRegisterOtp(new CustomerRegisterRequest("N", "A@X.com", "9999999", null), "123456");

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customers).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("a@x.com");
        assertThat(saved.getValue().getCustomerCode()).isEqualTo("C-1");
        assertThat(r.isNeedsProfile()).isTrue(); // address missing
    }

    @Test
    void refreshRotatesAndReturnsNewPair() {
        when(refresh.rotate("old", SubjectType.CUSTOMER))
                .thenReturn(new RefreshTokenService.Rotated(3L, "a@x.com", "CUSTOMER", "new-refresh"));
        when(customers.findById(3L)).thenReturn(Optional.of(active()));

        AuthResponse r = service.refresh("old");

        assertThat(r.getRefreshToken()).isEqualTo("new-refresh");
        assertThat(r.getToken()).isEqualTo("access");
    }

    @Test
    void refreshForDeactivatedCustomerKillsAllSessions() {
        Customer c = active();
        c.setStatus(AccountStatus.INACTIVE);
        when(refresh.rotate("old", SubjectType.CUSTOMER))
                .thenReturn(new RefreshTokenService.Rotated(3L, "a@x.com", "CUSTOMER", "new"));
        when(customers.findById(3L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.refresh("old")).isInstanceOf(ApiException.class);
        verify(refresh).revokeAll(3L, SubjectType.CUSTOMER);
    }

    @Test
    void refreshForDeletedCustomerIs401() {
        when(refresh.rotate("old", SubjectType.CUSTOMER))
                .thenReturn(new RefreshTokenService.Rotated(3L, "a@x.com", "CUSTOMER", "new"));
        when(customers.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refresh("old"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(401));
    }

    @Test
    void logoutRevokesRefreshTokenAndBlacklistsAccessJti() {
        when(jwt.extractJti("tok")).thenReturn("jti-1");
        when(jwt.extractExpirationDate("tok")).thenReturn(new Date(System.currentTimeMillis() + 60_000));

        service.logout("tok", "refresh-raw");

        verify(refresh).revoke("refresh-raw");
        verify(blacklist).revoke(eq("jti-1"), any());
    }

    @Test
    void logoutWithGarbageAccessTokenStillSucceeds() {
        when(jwt.extractJti("bad")).thenThrow(new RuntimeException("bad token"));

        service.logout("bad", "refresh-raw");

        verify(refresh).revoke("refresh-raw");
        verify(blacklist, never()).revoke(any(), any());
    }

    @Test
    void googleSignInWithUnverifiedEmailIsRejected() {
        when(google.verify("id")).thenReturn(new GoogleAuthService.GoogleIdentity("g1", "a@x.com", false, "N"));
        assertThatThrownBy(() -> service.authenticateWithGoogle("id")).isInstanceOf(ApiException.class);
        verify(customers, never()).save(any());
    }

    @Test
    void googleSignInLinksExistingEmailAccount() {
        Customer existing = active();
        when(google.verify("id")).thenReturn(new GoogleAuthService.GoogleIdentity("g1", "A@x.com", true, "N"));
        when(customers.findByGoogleId("g1")).thenReturn(Optional.empty());
        when(customers.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(existing));

        service.authenticateWithGoogle("id");

        assertThat(existing.getGoogleId()).isEqualTo("g1");
    }

    @Test
    void googleSignInCreatesNewLowercasedAccount() {
        when(google.verify("id")).thenReturn(new GoogleAuthService.GoogleIdentity("g1", "New@X.com", true, "N"));
        when(customers.findByGoogleId("g1")).thenReturn(Optional.empty());
        when(customers.findByEmailIgnoreCase("new@x.com")).thenReturn(Optional.empty());
        when(codes.nextCustomerCode()).thenReturn("C-9");

        AuthResponse r = service.authenticateWithGoogle("id");

        ArgumentCaptor<Customer> saved = ArgumentCaptor.forClass(Customer.class);
        verify(customers).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("new@x.com");
        assertThat(r.isNeedsProfile()).isTrue();
    }

    @Test
    void googleSignInRaceRetriesOnceAndFindsWinner() {
        Customer winner = active();
        winner.setGoogleId("g1");
        when(google.verify("id")).thenReturn(new GoogleAuthService.GoogleIdentity("g1", "a@x.com", true, "N"));
        when(customers.findByGoogleId("g1")).thenReturn(Optional.empty(), Optional.of(winner));
        when(customers.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.empty());
        when(codes.nextCustomerCode()).thenReturn("C-1");
        when(customers.save(any(Customer.class)))
                .thenThrow(new DataIntegrityViolationException("dup"))
                .thenAnswer(i -> i.getArgument(0));

        AuthResponse r = service.authenticateWithGoogle("id");

        assertThat(r.getUserId()).isEqualTo(3L);
    }
}
