package com.oorja.backend.service;

import com.oorja.backend.config.JwtService;
import com.oorja.backend.dto.response.AuthResponse;
import com.oorja.backend.entity.Admin;
import com.oorja.backend.entity.enums.OtpPurpose;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.AdminRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
class AdminAuthServiceTest {

    @Mock AdminRepository admins;
    @Mock OtpService otp;
    @Mock JwtService jwt;
    @Mock RefreshTokenService refresh;
    @Mock TokenBlacklistService blacklist;
    AdminAuthService service;

    @BeforeEach
    void setUp() {
        service = new AdminAuthService(admins, otp, jwt, refresh, blacklist);
        ReflectionTestUtils.setField(service, "adminPassword", "s3cret-pass");
        ReflectionTestUtils.setField(service, "defaultAdminEmail", "admin@oorja.test");
        ReflectionTestUtils.setField(service, "accessTokenExpirationMs", 900_000L);
        lenient().when(jwt.generateToken(anyString(), any(), anyString())).thenReturn("access");
        lenient().when(refresh.issue(any(), any(), anyString(), anyString())).thenReturn("refresh");
        lenient().when(admins.save(any(Admin.class))).thenAnswer(i -> {
            Admin a = i.getArgument(0);
            a.setId(1L);
            return a;
        });
    }

    @Test
    void wrongPasswordIs401AndIssuesNothing() {
        assertThatThrownBy(() -> service.loginWithPassword("nope"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(401));
        assertThatThrownBy(() -> service.loginWithPassword(null)).isInstanceOf(ApiException.class);
        verify(refresh, never()).issue(any(), any(), any(), any());
    }

    @Test
    void correctPasswordCreatesDefaultAdminOnFirstUseAndIssuesSuperAdminTokens() {
        when(admins.findByEmailIgnoreCase("admin@oorja.test")).thenReturn(Optional.empty());

        AuthResponse r = service.loginWithPassword("s3cret-pass");

        verify(admins).save(any(Admin.class));
        verify(jwt).generateToken("admin@oorja.test", 1L, "SUPER_ADMIN");
        assertThat(r.getRole()).isEqualTo("SUPER_ADMIN");
        assertThat(r.getRefreshToken()).isEqualTo("refresh");
    }

    @Test
    void correctPasswordReusesExistingAdmin() {
        Admin a = Admin.builder().id(9L).name("A").email("admin@oorja.test").role("SUPER_ADMIN").build();
        when(admins.findByEmailIgnoreCase("admin@oorja.test")).thenReturn(Optional.of(a));

        service.loginWithPassword("s3cret-pass");

        verify(admins, never()).save(any());
        verify(jwt).generateToken("admin@oorja.test", 9L, "SUPER_ADMIN");
    }

    @Test
    void otpRequestForUnknownAdminIs404() {
        when(admins.findByEmailIgnoreCase("x@x.com")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestOtp("x@x.com"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
    }

    @Test
    void otpLoginVerifiesCodeThenIssuesTokens() {
        Admin a = Admin.builder().id(9L).name("A").email("a@x.com").role("SUPER_ADMIN").build();
        when(admins.findByEmailIgnoreCase("a@x.com")).thenReturn(Optional.of(a));

        AuthResponse r = service.verifyOtpAndLogin("a@x.com", "123456");

        verify(otp).verify("a@x.com", "123456", OtpPurpose.ADMIN_LOGIN);
        assertThat(r.getUserId()).isEqualTo(9L);
    }

    @Test
    void refreshUsesAdminSubjectTypeAndPreservesRole() {
        when(refresh.rotate("old", SubjectType.ADMIN))
                .thenReturn(new RefreshTokenService.Rotated(9L, "a@x.com", "SUPER_ADMIN", "new"));

        AuthResponse r = service.refresh("old");

        verify(jwt).generateToken("a@x.com", 9L, "SUPER_ADMIN");
        assertThat(r.getRefreshToken()).isEqualTo("new");
    }

    @Test
    void logoutBlacklistsJtiAndToleratesBadToken() {
        when(jwt.extractJti("tok")).thenReturn("j");
        when(jwt.extractExpirationDate("tok")).thenReturn(new Date(System.currentTimeMillis() + 1000));
        service.logout("tok", "r");
        verify(blacklist).revoke(eq("j"), any());

        when(jwt.extractJti("bad")).thenThrow(new RuntimeException("x"));
        service.logout("bad", "r");
        verify(refresh, org.mockito.Mockito.times(2)).revoke("r");
    }
}
