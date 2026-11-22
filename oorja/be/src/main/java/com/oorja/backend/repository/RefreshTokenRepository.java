package com.oorja.backend.repository;

import com.oorja.backend.entity.RefreshToken;
import com.oorja.backend.entity.enums.SubjectType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Row-locking variant used by RefreshTokenService#rotate, so two
     * simultaneous refreshes of the same token can't both pass the
     * !isRevoked check and both mint a child token — defeating single-use
     * rotation.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now " +
           "where t.subjectId = :subjectId and t.subjectType = :subjectType and t.revokedAt is null")
    void revokeAllForSubject(@Param("subjectId") Long subjectId,
                              @Param("subjectType") SubjectType subjectType,
                              @Param("now") LocalDateTime now);

    /** Keeps revoked rows around briefly (for audit/reuse-detection) then purges. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RefreshToken t where t.expiresAt < :cutoff")
    void deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
