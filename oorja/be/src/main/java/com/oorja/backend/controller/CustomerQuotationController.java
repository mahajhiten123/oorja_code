package com.oorja.backend.controller;

import com.oorja.backend.dto.request.CreateRevisionRequestRequest;
import com.oorja.backend.dto.request.RecordQuotationPaymentRequest;
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
@RequestMapping("/api/customer/quotations")
@RequiredArgsConstructor
public class CustomerQuotationController {

    private final QuotationService quotationService;
    private final QuotationRevisionRequestService revisionRequestService;

    @GetMapping
    public List<QuotationResponse> myQuotations() {
        return quotationService.findByCustomer(CurrentUser.get().userId());
    }

    /** Fetching a sent quotation flips it to VIEWED, so admins can see it was actually opened. */
    @GetMapping("/{id}")
    public QuotationResponse getOne(@PathVariable Long id) {
        return quotationService.findByIdForCustomer(id, CurrentUser.get().userId());
    }

    @PostMapping("/{id}/accept")
    public QuotationResponse accept(@PathVariable Long id) {
        return quotationService.acceptByCustomer(id, CurrentUser.get().userId());
    }

    @PostMapping("/{id}/reject")
    public QuotationResponse reject(@PathVariable Long id) {
        return quotationService.rejectByCustomer(id, CurrentUser.get().userId());
    }

    /** Called after Razorpay's payment for an accepted quote has been verified. */
    @PostMapping("/{id}/record-payment")
    public QuotationResponse recordPayment(@PathVariable Long id, @Valid @RequestBody RecordQuotationPaymentRequest request) {
        return quotationService.recordPayment(id, CurrentUser.get().userId(), request.razorpayOrderId());
    }

    // ---------- Version history ----------

    /** Revision history for this quotation. Never includes an in-progress admin draft. */
    @GetMapping("/{id}/versions")
    public List<QuotationVersionResponse> versions(@PathVariable Long id) {
        return quotationService.listVersions(id, false);
    }

    @GetMapping("/{id}/versions/{fromVersion}/diff/{toVersion}")
    public QuotationVersionDiffResponse diffVersions(@PathVariable Long id, @PathVariable Integer fromVersion,
                                                      @PathVariable Integer toVersion) {
        return quotationService.diffVersions(id, fromVersion, toVersion);
    }

    // ---------- Revision requests ----------

    /** "Request Revision" on a sent quote — doesn't change anything itself, just flags it for the admin. */
    @PostMapping("/{id}/revision-requests")
    public QuotationRevisionRequestResponse requestRevision(@PathVariable Long id,
                                                              @Valid @RequestBody CreateRevisionRequestRequest request) {
        return revisionRequestService.create(id, CurrentUser.get().userId(), request);
    }

    @GetMapping("/{id}/revision-requests")
    public List<QuotationRevisionRequestResponse> myRevisionRequests(@PathVariable Long id) {
        return revisionRequestService.listForCustomer(id, CurrentUser.get().userId());
    }
}
