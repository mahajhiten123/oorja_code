package com.oorja.backend.controller;

import com.oorja.backend.dto.request.ResolveRevisionRequestRequest;
import com.oorja.backend.dto.request.SaveQuotationDraftRequest;
import com.oorja.backend.dto.request.SendQuotationVersionRequest;
import com.oorja.backend.dto.request.UpdateQuotationStatusRequest;
import com.oorja.backend.dto.response.QuotationResponse;
import com.oorja.backend.dto.response.QuotationRevisionRequestResponse;
import com.oorja.backend.dto.response.QuotationVersionDiffResponse;
import com.oorja.backend.dto.response.QuotationVersionResponse;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.QuotationRevisionRequestService;
import com.oorja.backend.service.QuotationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/quotations")
@RequiredArgsConstructor
public class AdminQuotationController {

    private final QuotationService quotationService;
    private final QuotationRevisionRequestService revisionRequestService;

    @GetMapping
    public List<QuotationResponse> list() {
        return quotationService.findAllForAdmin();
    }

    @GetMapping("/{id}")
    public QuotationResponse getOne(@PathVariable Long id) {
        return quotationService.findByIdForAdmin(id);
    }

    /**
     * Saves the current editable draft version without notifying the
     * customer. Doesn't affect what the customer sees — see POST /send for
     * that.
     */
    @PutMapping("/{id}/draft")
    public QuotationResponse saveDraft(@PathVariable Long id, @Valid @RequestBody SaveQuotationDraftRequest request) {
        return quotationService.saveDraft(id, CurrentUser.get().userId(), request);
    }

    /**
     * Prices (or re-prices) a quotation and emails it to the customer —
     * creating a new version automatically if the current one has already
     * been sent before. Pass {@code revisionRequestId} to resolve the
     * customer's open revision request in the same action.
     */
    @PostMapping("/{id}/send")
    public QuotationResponse send(@PathVariable Long id, @Valid @RequestBody SendQuotationVersionRequest request) {
        return quotationService.sendQuote(id, CurrentUser.get().userId(), request);
    }

    /** Quick actions from the admin console: mark accepted/rejected without waiting on the customer portal. */
    @PostMapping("/{id}/status")
    public QuotationResponse updateStatus(@PathVariable Long id, @Valid @RequestBody UpdateQuotationStatusRequest request) {
        return quotationService.updateStatusByAdmin(id, request.status());
    }

    // ---------- Version history ----------

    /** Full revision history, including the in-progress draft (if any). */
    @GetMapping("/{id}/versions")
    public List<QuotationVersionResponse> versions(@PathVariable Long id) {
        return quotationService.listVersions(id, true);
    }

    @GetMapping("/{id}/versions/{fromVersion}/diff/{toVersion}")
    public QuotationVersionDiffResponse diffVersions(@PathVariable Long id, @PathVariable Integer fromVersion,
                                                      @PathVariable Integer toVersion) {
        return quotationService.diffVersions(id, fromVersion, toVersion);
    }

    // ---------- Revision requests ----------

    @GetMapping("/{id}/revision-requests")
    public List<QuotationRevisionRequestResponse> revisionRequests(@PathVariable Long id) {
        return revisionRequestService.listForQuotation(id);
    }

    @PostMapping("/revision-requests/{requestId}/start-review")
    public QuotationRevisionRequestResponse startReview(@PathVariable Long requestId) {
        return revisionRequestService.startReview(requestId);
    }

    /** Declines to action a revision request — no new version is created. */
    @PostMapping("/revision-requests/{requestId}/reject")
    public QuotationRevisionRequestResponse rejectRevisionRequest(@PathVariable Long requestId,
                                                                   @Valid @RequestBody(required = false) ResolveRevisionRequestRequest request) {
        return revisionRequestService.reject(requestId, CurrentUser.get().userId(), request);
    }
}
