package com.oorja.backend.service;

import com.oorja.backend.entity.RefreshToken;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.RefreshTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock RefreshTokenRepository repo;
    RefreshTokenService service;

    @BeforeEach
    void setUp() {
        service = new RefreshTokenService(repo);
        ReflectionTestUtils.setField(service, "refreshExpirationMs", 60_000L);
    }

    private RefreshToken row(SubjectType type, LocalDateTime expires, LocalDateTime revoked) {
        return RefreshToken.builder().id(1L).subjectId(5L).subjectType(type).email("a@x.com").role("CUSTOMER")
                .tokenHash("h").expiresAt(expires).revokedAt(revoked).build();
    }

    @Test
    void issueStoresOnlyAHashOfTheRawToken() {
        String raw = service.issue(5L, SubjectType.CUSTOMER, "a@x.com", "CUSTOMER");

        ArgumentCaptor<RefreshToken> c = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repo).save(c.capture());
        assertThat(raw).isNotBlank();
        assertThat(c.getValue().getTokenHash()).hasSize(64).isNotEqualTo(raw);
        assertThat(c.getValue().getSubjectId()).isEqualTo(5L);
    }

    @Test
    void issuedTokensAreUnique() {
        assertThat(service.issue(1L, SubjectType.CUSTOMER, "a", "CUSTOMER"))
                .isNotEqualTo(service.issue(1L, SubjectType.CUSTOMER, "a", "CUSTOMER"));
    }

    @Test
    void rotateRevokesPresentedTokenAndIssuesNewOne() {
        RefreshToken existing = row(SubjectType.CUSTOMER, LocalDateTime.now().plusMinutes(5), null);
        when(repo.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(existing));

        RefreshTokenService.Rotated r = service.rotate("raw", SubjectType.CUSTOMER);

        assertThat(existing.isRevoked()).isTrue();
        assertThat(r.newRefreshToken()).isNotBlank().isNotEqualTo("raw");
        assertThat(r.subjectId()).isEqualTo(5L);
        assertThat(r.role()).isEqualTo("CUSTOMER");
    }

    @Test
    void reuseOfRotatedTokenRevokesEveryLiveSessionForTheSubject() {
        RefreshToken existing = row(SubjectType.CUSTOMER, LocalDateTime.now().plusMinutes(5), LocalDateTime.now().minusSeconds(5));
        when(repo.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.rotate("raw", SubjectType.CUSTOMER)).isInstanceOf(ApiException.class);

        verify(repo).revokeAllForSubject(eq(5L), eq(SubjectType.CUSTOMER), any());
    }

    @Test
    void expiredTokenIsRejected() {
        RefreshToken existing = row(SubjectType.CUSTOMER, LocalDateTime.now().minusMinutes(1), null);
        when(repo.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.rotate("raw", SubjectType.CUSTOMER))
                .isInstanceOf(ApiException.class).hasMessageContaining("expired");
        verify(repo, never()).save(any());
    }

    @Test
    void tokenOfTheWrongSubjectTypeIsRejected() {
        RefreshToken existing = row(SubjectType.ADMIN, LocalDateTime.now().plusMinutes(5), null);
        when(repo.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.rotate("raw", SubjectType.CUSTOMER)).isInstanceOf(ApiException.class);
        assertThat(existing.isRevoked()).isFalse();
    }

    @Test
    void unknownBlankAndNullTokensAreRejected() {
        when(repo.findByTokenHashForUpdate(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rotate("nope", SubjectType.CUSTOMER)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.rotate("  ", SubjectType.CUSTOMER)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.rotate(null, SubjectType.CUSTOMER)).isInstanceOf(ApiException.class);
    }

    @Test
    void revokeIgnoresUnknownOrBlankTokens() {
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.empty());

        service.revoke("unknown");
        service.revoke(null);

        verify(repo, never()).save(any());
    }

    @Test
    void revokeMarksKnownTokenRevoked() {
        RefreshToken existing = row(SubjectType.CUSTOMER, LocalDateTime.now().plusMinutes(5), null);
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(existing));

        service.revoke("raw");

        assertThat(existing.isRevoked()).isTrue();
        verify(repo).save(existing);
    }
}
