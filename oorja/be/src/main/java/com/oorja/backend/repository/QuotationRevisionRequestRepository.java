package com.oorja.backend.repository;

import com.oorja.backend.entity.QuotationRevisionRequest;
import com.oorja.backend.entity.enums.RevisionRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuotationRevisionRequestRepository extends JpaRepository<QuotationRevisionRequest, Long> {

    List<QuotationRevisionRequest> findByQuotationIdOrderByCreatedAtDesc(Long quotationId);

    List<QuotationRevisionRequest> findByQuotationIdAndStatusInOrderByCreatedAtDesc(
            Long quotationId, List<RevisionRequestStatus> statuses);
}
