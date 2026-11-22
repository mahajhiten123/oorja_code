package com.oorja.backend.repository;

import com.oorja.backend.entity.RevokedAccessToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface RevokedAccessTokenRepository extends JpaRepository<RevokedAccessToken, String> {

    /** Race-safe insert: two concurrent logouts of the same token both succeed, one row results. */
    @Modifying
    @Query(value = "insert into revoked_access_tokens (jti, expires_at, revoked_at) " +
            "values (:jti, :expiresAt, now()) on conflict (jti) do nothing", nativeQuery = true)
    void insertIfAbsent(@Param("jti") String jti, @Param("expiresAt") LocalDateTime expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RevokedAccessToken t where t.expiresAt < :cutoff")
    void deleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
