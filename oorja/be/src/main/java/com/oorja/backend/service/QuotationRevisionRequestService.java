package com.oorja.backend.service;

import com.oorja.backend.dto.request.CreateRevisionRequestRequest;
import com.oorja.backend.dto.request.ResolveRevisionRequestRequest;
import com.oorja.backend.dto.response.QuotationRevisionRequestResponse;
import com.oorja.backend.entity.Quotation;
import com.oorja.backend.entity.QuotationRevisionRequest;
import com.oorja.backend.entity.QuotationVersion;
import com.oorja.backend.entity.enums.QuotationStatus;
import com.oorja.backend.entity.enums.RevisionRequestStatus;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.CustomerRepository;
import com.oorja.backend.repository.QuotationRevisionRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Customer-initiated "please change this" requests against a sent
 * quotation. A request never mutates the quotation itself — it's a message
 * plus a paper trail; the admin acts on it by either creating and sending a
 * new {@link QuotationVersion} (see {@code QuotationService.sendQuote}'s
 * {@code revisionRequestId} parameter, which resolves the request as part of
 * that same transaction) or explicitly rejecting it here.
 */
@Service
@RequiredArgsConstructor
public class QuotationRevisionRequestService {

    private final QuotationRevisionRequestRepository revisionRequestRepository;
    private final CustomerRepository customerRepository;
    private final QuotationService quotationService;

    private static final Set<QuotationStatus> REVISABLE = Set.of(QuotationStatus.SENT, QuotationStatus.VIEWED);

    @Transactional
    public QuotationRevisionRequestResponse create(Long quotationId, Long customerId, CreateRevisionRequestRequest request) {
        Quotation quotation = quotationService.getOrThrow(quotationId);
        if (!quotation.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("This quotation does not belong to your account.");
        }
        if (!REVISABLE.contains(quotation.getStatus())) {
            throw ApiException.badRequest(
                    "You can only request a revision on a quotation that's currently awaiting your response.");
        }

        QuotationVersion current = quotationService.getCurrentVersion(quotation);
        var customer = customerRepository.findById(customerId)
                .orElseThrow(() -> ApiException.notFound("Customer not found."));

        QuotationRevisionRequest saved = revisionRequestRepository.save(QuotationRevisionRequest.builder()
                .quotation(quotation)
                .customer(customer)
                .quotationVersion(current)
                .reason(request.reason())
                .message(request.message())
                .status(RevisionRequestStatus.OPEN)
                .build());

        return QuotationRevisionRequestResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<QuotationRevisionRequestResponse> listForQuotation(Long quotationId) {
        return revisionRequestRepository.findByQuotationIdOrderByCreatedAtDesc(quotationId).stream()
                .map(QuotationRevisionRequestResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<QuotationRevisionRequestResponse> listForCustomer(Long quotationId, Long customerId) {
        Quotation quotation = quotationService.getOrThrow(quotationId);
        if (!quotation.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("This quotation does not belong to your account.");
        }
        return listForQuotation(quotationId);
    }

    /** Admin marks a request as being looked at, without resolving it yet. */
    @Transactional
    public QuotationRevisionRequestResponse startReview(Long requestId) {
        QuotationRevisionRequest request = getOrThrow(requestId);
        if (request.getStatus() != RevisionRequestStatus.OPEN) {
            throw ApiException.badRequest("Only an open request can be moved to in-review.");
        }
        request.setStatus(RevisionRequestStatus.IN_REVIEW);
        return QuotationRevisionRequestResponse.from(revisionRequestRepository.save(request));
    }

    /** Admin declines to action a revision request — no new version is created. */
    @Transactional
    public QuotationRevisionRequestResponse reject(Long requestId, Long adminId, ResolveRevisionRequestRequest request) {
        QuotationRevisionRequest revisionRequest = getOrThrow(requestId);
        if (revisionRequest.getStatus() == RevisionRequestStatus.RESOLVED
                || revisionRequest.getStatus() == RevisionRequestStatus.REJECTED) {
            throw ApiException.badRequest("This request has already been resolved.");
        }
        revisionRequest.setStatus(RevisionRequestStatus.REJECTED);
        revisionRequest.setResolutionNote(request != null ? request.note() : null);
        revisionRequest.setResolvedAt(LocalDateTime.now());
        revisionRequest.setResolvedBy(adminId);
        return QuotationRevisionRequestResponse.from(revisionRequestRepository.save(revisionRequest));
    }

    private QuotationRevisionRequest getOrThrow(Long id) {
        return revisionRequestRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Revision request not found: " + id));
    }
}
