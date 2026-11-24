package com.oorja.backend.controller;

import com.oorja.backend.dto.request.AdminRefundRequest;
import com.oorja.backend.dto.request.ApproveRefundRequest;
import com.oorja.backend.dto.request.RejectRefundRequest;
import com.oorja.backend.dto.response.RefundReconciliationResponse;
import com.oorja.backend.dto.response.RefundResponse;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.RefundDispatchService;
import com.oorja.backend.service.RefundReconciliationJob;
import com.oorja.backend.service.RefundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin console → Refunds. Stage 4 (approval) and the operational tooling
 * around stages 5 and 8: retry a stuck gateway refund, and see whether the
 * books balance.
 */
@RestController
@RequestMapping("/api/admin/refunds")
@RequiredArgsConstructor
public class AdminRefundController {

    private final RefundService refundService;
    private final RefundDispatchService dispatchService;
    private final RefundReconciliationJob reconciliationJob;

    @GetMapping
    public Page<RefundResponse> getAll(@PageableDefault(size = 500) Pageable pageable) {
        return refundService.findAll(pageable);
    }

    /** Just the ones that need a human: requested, under review, or failed. */
    @GetMapping("/queue")
    public List<RefundResponse> queue() {
        return refundService.findQueue();
    }

    @PostMapping("/{id}/review")
    public RefundResponse startReview(@PathVariable Long id) {
        return refundService.startReview(id, actor());
    }

    @PostMapping("/{id}/approve")
    public RefundResponse approve(@PathVariable Long id,
                                  @Valid @RequestBody(required = false) ApproveRefundRequest request) {
        return refundService.approve(id,
                request == null ? null : request.amountPaiseOrNull(),
                request == null ? null : request.note(),
                actor());
    }

    @PostMapping("/{id}/reject")
    public RefundResponse reject(@PathVariable Long id,
                                 @Valid @RequestBody(required = false) RejectRefundRequest request) {
        return refundService.reject(id, request != null ? request.reason() : null, actor());
    }

    /**
     * Force another gateway attempt on a refund that's stuck. Safe to press
     * repeatedly — the refund's idempotency key means the provider returns
     * the existing refund rather than issuing a second one.
     */
    @PostMapping("/{id}/retry")
    public RefundResponse retry(@PathVariable Long id) {
        dispatchService.dispatch(id);
        return refundService.findOne(id);
    }

    /** Raise a refund ourselves — duplicate charge, service failure, goodwill. */
    @PostMapping
    public RefundResponse create(@Valid @RequestBody AdminRefundRequest request) {
        return refundService.createByAdmin(request, actor());
    }

    /** Stage 8, on demand: the money position and anything that doesn't add up. */
    @GetMapping("/reconciliation")
    public RefundReconciliationResponse reconciliation() {
        return reconciliationJob.buildReport(false);
    }

    private String actor() {
        var user = CurrentUser.get();
        return user != null && user.email() != null ? user.email() : "admin";
    }
}
