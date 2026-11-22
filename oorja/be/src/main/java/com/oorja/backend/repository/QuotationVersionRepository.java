package com.oorja.backend.repository;

import com.oorja.backend.entity.QuotationVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuotationVersionRepository extends JpaRepository<QuotationVersion, Long> {

    List<QuotationVersion> findByQuotationIdOrderByVersionNumberAsc(Long quotationId);

    Optional<QuotationVersion> findByQuotationIdAndVersionNumber(Long quotationId, Integer versionNumber);

    /** Highest version number so far for a quotation — used to compute the next one. */
    Optional<QuotationVersion> findFirstByQuotationIdOrderByVersionNumberDesc(Long quotationId);
}
