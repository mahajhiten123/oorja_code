package com.oorja.backend.repository;

import com.oorja.backend.entity.OtpToken;
import com.oorja.backend.entity.enums.OtpPurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OtpTokenRepository extends JpaRepository<OtpToken, Long> {

    Optional<OtpToken> findTopByIdentifierIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDesc(
            String identifier, OtpPurpose purpose);

    /**
     * Row-locking variant of the above, used by OtpService#verify so two
     * concurrent verifies of the same still-unused token can't both read
     * used=false and both succeed, and so the attempts counter's
     * read-then-write increment can't lose updates under concurrent
     * wrong attempts. (JPQL, since a derived-query name can't carry a
     * "for update" suffix.)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from OtpToken t where lower(t.identifier) = lower(:identifier) and t.purpose = :purpose " +
           "and t.used = false order by t.createdAt desc")
    List<OtpToken> findTopByIdentifierIgnoreCaseAndPurposeAndUsedFalseOrderByCreatedAtDescForUpdate(
            @org.springframework.data.repository.query.Param("identifier") String identifier,
            @org.springframework.data.repository.query.Param("purpose") OtpPurpose purpose);

    /**
     * Atomic, lock-free increment for a wrong-OTP attempt — mirrors the
     * slot-claim UPDATE pattern in SlotService. Combined with the row lock
     * held by the caller's transaction (see the ForUpdate query above) this
     * belongs to the same fix, but is written as a single UPDATE ... WHERE
     * so the counter is correct even if this method is ever called from a
     * path that doesn't hold that lock. Returns the number of rows updated
     * (0 if the token was concurrently marked used).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OtpToken t set t.attempts = t.attempts + 1 where t.id = :id and t.used = false")
    int incrementAttempts(@org.springframework.data.repository.query.Param("id") Long id);

    @Modifying
    @Query("delete from OtpToken t where t.expiresAt < :cutoff")
    void deleteExpired(LocalDateTime cutoff);

    @Query("select count(t) from OtpToken t where t.identifier = :identifier and t.purpose = :purpose " +
           "and t.createdAt > :since")
    long countRecentRequests(String identifier, OtpPurpose purpose, LocalDateTime since);
}
