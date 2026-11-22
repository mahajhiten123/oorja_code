package com.oorja.backend.service;

import com.oorja.backend.dto.request.SaveQuotationDraftRequest;
import com.oorja.backend.dto.request.SendQuotationVersionRequest;
import com.oorja.backend.dto.response.BookingAttachmentResponse;
import com.oorja.backend.dto.response.QuotationResponse;
import com.oorja.backend.dto.response.QuotationVersionDiffResponse;
import com.oorja.backend.dto.response.QuotationVersionResponse;
import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.Payment;
import com.oorja.backend.entity.Quotation;
import com.oorja.backend.entity.QuotationVersion;
import com.oorja.backend.entity.enums.QuotationStatus;
import com.oorja.backend.entity.enums.QuotationVersionStatus;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingAttachmentRepository;
import com.oorja.backend.repository.BookingRepository;
import com.oorja.backend.repository.QuotationRepository;
import com.oorja.backend.repository.QuotationRevisionRequestRepository;
import com.oorja.backend.repository.QuotationVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Owns the {@link Quotation} <-> {@link QuotationVersion} lifecycle.
 *
 * <p><b>The one rule that matters:</b> a {@link QuotationVersion}'s financial
 * fields (subtotal/discount/tax/total/notes/validUntil) are written only
 * while it is {@code DRAFT}. Once sent, that row is frozen forever — a price
 * change always means creating version N+1, never editing version N. See
 * {@link #getOrCreateEditableDraft} for where that's enforced, and
 * {@link #syncQuotationFromVersion} for how {@code Quotation}'s own fields
 * stay a cheap, queryable mirror of whichever version is current.
 *
 * <p>Once any version of a quotation has been paid, the whole quotation is
 * permanently locked — no further version may ever be created — because the
 * financial history behind a real Razorpay payment must never change.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuotationService {

    private final QuotationRepository quotationRepository;
    private final QuotationVersionRepository quotationVersionRepository;
    private final QuotationRevisionRequestRepository revisionRequestRepository;
    private final BookingAttachmentRepository bookingAttachmentRepository;
    private final BookingRepository bookingRepository;
    private final CodeGeneratorService codeGeneratorService;
    private final EmailService emailService;
    private final PaymentService paymentService;

    private static final Set<QuotationStatus> RESPONDABLE = Set.of(QuotationStatus.SENT, QuotationStatus.VIEWED);
    private static final Set<QuotationVersionStatus> RESPONDABLE_VERSION =
            Set.of(QuotationVersionStatus.SENT, QuotationVersionStatus.VIEWED);

    // ---------- Created automatically when a customer submits a quote request ----------

    @Transactional
    public Quotation createDraftForBooking(Booking booking) {
        Quotation quotation = Quotation.builder()
                .quotationNumber(codeGeneratorService.nextQuotationNumber())
                .booking(booking)
                .customer(booking.getCustomer())
                .status(QuotationStatus.DRAFT)
                .discountAmount(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .currentVersionNumber(1)
                .build();
        quotation = quotationRepository.save(quotation);

        QuotationVersion v1 = QuotationVersion.builder()
                .quotation(quotation)
                .versionNumber(1)
                .discountAmount(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .status(QuotationVersionStatus.DRAFT)
                .build();
        quotationVersionRepository.save(v1);

        // Best-effort notifications: the request exists in the database
        // either way, so a mail hiccup here must never fail the booking.
        try {
            emailService.sendQuotationRequestReceivedEmail(
                    booking.getCustomer().getEmail(),
                    booking.getCustomer().getName(),
                    quotation.getQuotationNumber(),
                    booking.getServiceLabel(),
                    booking.getToken());
            emailService.sendQuotationRequestAlertToAdmin(
                    quotation.getQuotationNumber(),
                    booking.getCustomer().getName(),
                    booking.getCustomer().getEmail(),
                    booking.getCustomer().getPhone(),
                    booking.getServiceLabel(),
                    booking.getLocation(),
                    booking.getToken());
        } catch (Exception e) {
            log.error("Couldn't send quotation-request notification emails for {}: {}",
                    quotation.getQuotationNumber(), e.getMessage());
        }

        return quotation;
    }

    // ---------- Admin ----------

    @Transactional(readOnly = true)
    public List<QuotationResponse> findAllForAdmin() {
        return quotationRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public QuotationResponse findByIdForAdmin(Long id) {
        return toResponse(getOrThrow(id));
    }

    /** Admin "Save Draft" — edits the current editable draft version without notifying the customer. */
    @Transactional
    public QuotationResponse saveDraft(Long id, Long adminId, SaveQuotationDraftRequest request) {
        Quotation quotation = getOrThrow(id);
        QuotationVersion draft = getOrCreateEditableDraft(quotation, adminId);

        applyFinancials(draft, request.amountInr(), request.subtotal(), request.taxAmount(),
                request.discountAmount(), request.validUntil(), request.notes());

        quotationVersionRepository.save(draft);
        return toResponse(quotation);
    }

    /**
     * Admin "Send to Customer" — fills in (or creates, if needed) the
     * editable draft version with the given values and sends it. This is
     * both the "price it for the first time" flow and the "send a revision"
     * flow; the only difference is whether a DRAFT already existed.
     */
    @Transactional
    public QuotationResponse sendQuote(Long id, Long adminId, SendQuotationVersionRequest request) {
        Quotation quotation = getOrThrow(id);
        QuotationVersion draft = getOrCreateEditableDraft(quotation, adminId);

        applyFinancials(draft, request.amountInr(), request.subtotal(), request.taxAmount(),
                request.discountAmount(), request.validUntil(), request.notes());

        // Anything else outstanding (a previously sent/viewed/accepted-but-unpaid
        // version) is superseded the moment a new one goes out - only one
        // version is ever "live" at a time.
        for (QuotationVersion other : quotationVersionRepository.findByQuotationIdOrderByVersionNumberAsc(id)) {
            if (!other.getId().equals(draft.getId())
                    && (RESPONDABLE_VERSION.contains(other.getStatus()) || other.getStatus() == QuotationVersionStatus.ACCEPTED)) {
                other.setStatus(QuotationVersionStatus.SUPERSEDED);
                quotationVersionRepository.save(other);
            }
        }

        draft.setStatus(QuotationVersionStatus.SENT);
        draft.setSentAt(LocalDateTime.now());
        quotationVersionRepository.save(draft);

        quotation.setCurrentVersionNumber(draft.getVersionNumber());
        quotation.setAcceptedVersionId(null); // a fresh send always supersedes any prior acceptance
        syncQuotationFromVersion(quotation, draft);
        quotationRepository.save(quotation);

        if (request.revisionRequestId() != null) {
            resolveRevisionRequestInternal(request.revisionRequestId(), quotation.getId(), adminId,
                    "Resolved with quotation version v" + draft.getVersionNumber() + ".");
        }

        emailService.sendQuotationEmail(
                quotation.getCustomer().getEmail(),
                quotation.getCustomer().getName(),
                quotation.getQuotationNumber(),
                quotation.getTotalAmount(),
                quotation.getValidUntil(),
                quotation.getNotes());

        return toResponse(quotation);
    }

    /** Admin quick-actions: "Mark accepted" / "Mark rejected" on behalf of the customer (e.g. phone/offline confirmation). */
    @Transactional
    public QuotationResponse updateStatusByAdmin(Long id, QuotationStatus status) {
        if (status != QuotationStatus.ACCEPTED && status != QuotationStatus.REJECTED) {
            throw ApiException.badRequest("Status must be ACCEPTED or REJECTED.");
        }
        Quotation quotation = getOrThrow(id);
        if (!RESPONDABLE.contains(quotation.getStatus())) {
            throw ApiException.badRequest("Only a sent quotation can be accepted or rejected.");
        }
        QuotationVersion current = getCurrentVersion(quotation);
        applyResponse(quotation, current, status == QuotationStatus.ACCEPTED
                ? QuotationVersionStatus.ACCEPTED : QuotationVersionStatus.REJECTED);
        return toResponse(quotation);
    }

    // ---------- Versions & revision history (both portals) ----------

    /** Full revision history, oldest first. Admin sees the in-progress draft too; customers never do. */
    @Transactional(readOnly = true)
    public List<QuotationVersionResponse> listVersions(Long quotationId, boolean includeDraft) {
        Quotation quotation = getOrThrow(quotationId);
        return quotationVersionRepository.findByQuotationIdOrderByVersionNumberAsc(quotationId).stream()
                .filter(v -> includeDraft || v.getStatus() != QuotationVersionStatus.DRAFT)
                .map(v -> QuotationVersionResponse.from(v, v.getVersionNumber().equals(quotation.getCurrentVersionNumber())))
                .toList();
    }

    /** "View changes": the fields that actually differ between two versions of the same quotation. */
    @Transactional(readOnly = true)
    public QuotationVersionDiffResponse diffVersions(Long quotationId, Integer fromVersionNumber, Integer toVersionNumber) {
        QuotationVersion from = getVersionOrThrow(quotationId, fromVersionNumber);
        QuotationVersion to = getVersionOrThrow(quotationId, toVersionNumber);

        List<QuotationVersionDiffResponse.FieldChange> changes = new ArrayList<>();
        addIfChanged(changes, "subtotal", "Subtotal", from.getSubtotal(), to.getSubtotal());
        addIfChanged(changes, "discountAmount", "Discount", from.getDiscountAmount(), to.getDiscountAmount());
        addIfChanged(changes, "taxAmount", "Tax", from.getTaxAmount(), to.getTaxAmount());
        addIfChanged(changes, "amountInr", "Total", from.getTotalAmount(), to.getTotalAmount());
        addIfChanged(changes, "validUntil", "Valid until",
                from.getValidUntil() == null ? null : from.getValidUntil().toString(),
                to.getValidUntil() == null ? null : to.getValidUntil().toString());
        addIfChanged(changes, "notes", "Notes", from.getNotes(), to.getNotes());

        return QuotationVersionDiffResponse.builder()
                .fromVersion(fromVersionNumber)
                .toVersion(toVersionNumber)
                .changes(changes)
                .build();
    }

    // ---------- Customer ----------

    @Transactional(readOnly = true)
    public List<QuotationResponse> findByCustomer(Long customerId) {
        return quotationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public QuotationResponse findByIdForCustomer(Long id, Long customerId) {
        Quotation quotation = getOwnedByOrThrow(id, customerId);
        if (quotation.getStatus() == QuotationStatus.SENT) {
            QuotationVersion current = getCurrentVersion(quotation);
            current.setStatus(QuotationVersionStatus.VIEWED);
            current.setViewedAt(LocalDateTime.now());
            quotationVersionRepository.save(current);

            quotation.setStatus(QuotationStatus.VIEWED);
            quotation.setViewedAt(current.getViewedAt());
            quotation = quotationRepository.save(quotation);
        }
        return toResponse(quotation);
    }

    @Transactional
    public QuotationResponse acceptByCustomer(Long id, Long customerId) {
        // Locked read: without this, two concurrent accept calls on the same
        // quotation can both pass the RESPONDABLE status guard below.
        Quotation quotation = getOwnedByOrThrowForUpdate(id, customerId);
        if (!RESPONDABLE.contains(quotation.getStatus())) {
            throw ApiException.badRequest("This quotation isn't awaiting a response.");
        }
        QuotationVersion current = getCurrentVersion(quotation);
        if (current.getValidUntil() != null && current.getValidUntil().isBefore(LocalDate.now())) {
            current.setStatus(QuotationVersionStatus.EXPIRED);
            quotationVersionRepository.save(current);
            quotation.setStatus(QuotationStatus.EXPIRED);
            quotationRepository.save(quotation);
            throw ApiException.badRequest("This quotation has expired. Please request a fresh quote.");
        }
        applyResponse(quotation, current, QuotationVersionStatus.ACCEPTED);
        quotation.setAcceptedVersionId(current.getId());
        quotationRepository.save(quotation);
        return toResponse(quotation);
    }

    @Transactional
    public QuotationResponse rejectByCustomer(Long id, Long customerId) {
        Quotation quotation = getOwnedByOrThrow(id, customerId);
        if (!RESPONDABLE.contains(quotation.getStatus())) {
            throw ApiException.badRequest("This quotation isn't awaiting a response.");
        }
        QuotationVersion current = getCurrentVersion(quotation);
        applyResponse(quotation, current, QuotationVersionStatus.REJECTED);
        return toResponse(quotation);
    }

    /**
     * Called once the customer has paid (via the existing Razorpay flow) for
     * an accepted quote. Writes {@code paymentRef}/{@code paidAt} onto the
     * exact accepted {@link QuotationVersion} — never its financial fields —
     * which is what permanently locks that version (and this quotation) from
     * ever getting a new version created.
     */
    @Transactional
    public QuotationResponse recordPayment(Long id, Long customerId, String razorpayOrderId) {
        Quotation quotation = getOwnedByOrThrow(id, customerId);

        // Take the payment row lock FIRST. The Razorpay webhook locks the payment and then updates the quotation;
        // locking in the same order here means the two can never deadlock, and two concurrent record-payment calls
        // for the same order are serialized on this lock.
        Payment payment = paymentService.consumeCapturedQuotationPayment(customerId, id, razorpayOrderId);

        if (quotation.getPaymentRef() != null) {
            if (quotation.getPaymentRef().equals(payment.getRazorpayPaymentId())) {
                // Retry after a lost response (or the webhook already recorded it): same payment, same outcome.
                return toResponse(quotation);
            }
            throw ApiException.conflict("This quotation has already been paid for.", "ALREADY_PAID");
        }
        if (quotation.getStatus() != QuotationStatus.ACCEPTED || quotation.getAcceptedVersionId() == null) {
            throw ApiException.badRequest("This quotation must be accepted before it can be paid for.");
        }
        assertPaidAmountMatchesQuotation(quotation, payment);

        QuotationVersion accepted = quotationVersionRepository.findById(quotation.getAcceptedVersionId())
                .orElseThrow(() -> ApiException.notFound("Accepted quotation version not found."));
        accepted.setPaymentRef(payment.getRazorpayPaymentId());
        accepted.setPaidAt(LocalDateTime.now());
        quotationVersionRepository.save(accepted);

        quotation.setPaymentRef(payment.getRazorpayPaymentId());
        quotationRepository.save(quotation);

        Booking booking = quotation.getBooking();
        booking.setAmountPaise(payment.getAmountPaise());
        booking.setPaymentRef(payment.getRazorpayPaymentId());
        bookingRepository.save(booking);

        paymentService.linkToBooking(payment, booking);

        return toResponse(quotation);
    }

    /**
     * Webhook counterpart to {@link #recordPayment} — called when Razorpay
     * confirms a quotation payment CAPTURED but the customer's own
     * POST /record-payment call never arrived. Trusts the Payment row
     * itself (already signature-verified and row-locked by the caller)
     * rather than re-checking a customer id, since a webhook has no
     * logged-in customer to check against.
     */
    @Transactional
    public void finalizeFromWebhook(Payment payment) {
        if (payment.getQuotationId() == null) {
            return;
        }
        Quotation quotation = quotationRepository.findById(payment.getQuotationId()).orElse(null);
        if (quotation == null || quotation.getPaymentRef() != null || quotation.getAcceptedVersionId() == null) {
            return; // already recorded (e.g. by the client-driven flow), or the quotation is gone/unaccepted
        }
        if (!paidAmountMatches(quotation, payment)) {
            // The customer paid an order that was opened at an older price. Don't mark the quotation paid at the wrong
            // amount; the capture is on the ledger and the payment stays unlinked for ops to refund or re-quote.
            log.error("Payment {} ({} paise) does not match quotation {} total - leaving for manual review",
                    payment.getPaymentNumber(), payment.getAmountPaise(), quotation.getQuotationNumber());
            return;
        }

        QuotationVersion accepted = quotationVersionRepository.findById(quotation.getAcceptedVersionId()).orElse(null);
        if (accepted != null && accepted.getPaymentRef() == null) {
            accepted.setPaymentRef(payment.getRazorpayPaymentId());
            accepted.setPaidAt(LocalDateTime.now());
            quotationVersionRepository.save(accepted);
        }

        quotation.setPaymentRef(payment.getRazorpayPaymentId());
        quotationRepository.save(quotation);

        Booking booking = quotation.getBooking();
        booking.setAmountPaise(payment.getAmountPaise());
        booking.setPaymentRef(payment.getRazorpayPaymentId());
        bookingRepository.save(booking);

        paymentService.linkToBooking(payment, booking);
    }

    // ---------- Scheduled maintenance ----------

    /** Flips quotations whose validity window has lapsed without a customer response to EXPIRED. */
    @Transactional
    public void expireOverdue() {
        List<Quotation> overdue = quotationRepository.findByStatusInAndValidUntilBefore(
                List.of(QuotationStatus.SENT, QuotationStatus.VIEWED), LocalDate.now());
        for (Quotation quotation : overdue) {
            quotation.setStatus(QuotationStatus.EXPIRED);
            getCurrentVersion(quotation).setStatus(QuotationVersionStatus.EXPIRED);
        }
        quotationRepository.saveAll(overdue);
    }

    // ---------- Version helpers ----------

    /**
     * Returns the version admin edits should land on: the existing DRAFT if
     * the current version already is one, otherwise a freshly created
     * version one number higher. Refuses outright once the quotation has
     * been paid — see the class-level note on immutability.
     */
    QuotationVersion getOrCreateEditableDraft(Quotation quotation, Long adminId) {
        if (quotation.getPaymentRef() != null) {
            throw ApiException.badRequest(
                    "This quotation has already been paid for and its financial history can't be changed. " +
                    "If something needs to change, it needs a new booking/quotation.");
        }

        QuotationVersion current = getCurrentVersion(quotation);
        if (current.isDraft()) {
            return current;
        }

        int nextNumber = current.getVersionNumber() + 1;
        QuotationVersion draft = QuotationVersion.builder()
                .quotation(quotation)
                .versionNumber(nextNumber)
                .discountAmount(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .status(QuotationVersionStatus.DRAFT)
                .createdBy(adminId)
                .createdByType(SubjectType.ADMIN)
                .build();
        return quotationVersionRepository.save(draft);
    }

    private void applyFinancials(QuotationVersion version, BigDecimal amountInr, BigDecimal subtotal,
                                  BigDecimal tax, BigDecimal discount, LocalDate validUntil, String notes) {
        version.setSubtotal(subtotal != null ? subtotal : amountInr);
        version.setTaxAmount(tax != null ? tax : BigDecimal.ZERO);
        version.setDiscountAmount(discount != null ? discount : BigDecimal.ZERO);
        version.setTotalAmount(amountInr);
        if (validUntil != null) version.setValidUntil(validUntil);
        if (notes != null) version.setNotes(notes);
    }

    /** Keeps Quotation's mirrored fields in sync with whichever version is now current. */
    private void syncQuotationFromVersion(Quotation quotation, QuotationVersion version) {
        quotation.setSubtotal(version.getSubtotal());
        quotation.setTaxAmount(version.getTaxAmount());
        quotation.setDiscountAmount(version.getDiscountAmount());
        quotation.setTotalAmount(version.getTotalAmount());
        quotation.setValidUntil(version.getValidUntil());
        quotation.setNotes(version.getNotes());
        quotation.setStatus(mirrorStatus(version.getStatus()));
        quotation.setSentAt(version.getSentAt());
        quotation.setViewedAt(version.getViewedAt());
        quotation.setRespondedAt(version.getRespondedAt());
    }

    private QuotationStatus mirrorStatus(QuotationVersionStatus versionStatus) {
        return switch (versionStatus) {
            case DRAFT -> QuotationStatus.DRAFT;
            case SENT -> QuotationStatus.SENT;
            case VIEWED -> QuotationStatus.VIEWED;
            case ACCEPTED -> QuotationStatus.ACCEPTED;
            case REJECTED -> QuotationStatus.REJECTED;
            case EXPIRED -> QuotationStatus.EXPIRED;
            case SUPERSEDED -> QuotationStatus.SENT; // shouldn't be reachable as "current"
        };
    }

    private void applyResponse(Quotation quotation, QuotationVersion current, QuotationVersionStatus newStatus) {
        current.setStatus(newStatus);
        current.setRespondedAt(LocalDateTime.now());
        quotationVersionRepository.save(current);

        quotation.setStatus(mirrorStatus(newStatus));
        quotation.setRespondedAt(current.getRespondedAt());
        quotationRepository.save(quotation);
    }

    QuotationVersion getCurrentVersion(Quotation quotation) {
        return quotationVersionRepository
                .findByQuotationIdAndVersionNumber(quotation.getId(), quotation.getCurrentVersionNumber())
                .orElseThrow(() -> new IllegalStateException(
                        "Quotation " + quotation.getId() + " has no version " + quotation.getCurrentVersionNumber()));
    }

    private QuotationVersion getVersionOrThrow(Long quotationId, Integer versionNumber) {
        return quotationVersionRepository.findByQuotationIdAndVersionNumber(quotationId, versionNumber)
                .orElseThrow(() -> ApiException.notFound("Version v" + versionNumber + " not found."));
    }

    private void addIfChanged(List<QuotationVersionDiffResponse.FieldChange> changes, String field, String label,
                               Object from, Object to) {
        boolean same = (from == null && to == null) || (from != null && from.equals(to));
        if (same) return;
        changes.add(QuotationVersionDiffResponse.FieldChange.builder()
                .field(field)
                .label(label)
                .from(from == null ? null : from.toString())
                .to(to == null ? null : to.toString())
                .build());
    }

    /** Package-visible so QuotationRevisionRequestService can resolve a request as part of sendQuote's transaction. */
    void resolveRevisionRequestInternal(Long revisionRequestId, Long quotationId, Long adminId, String note) {
        revisionRequestRepository.findById(revisionRequestId).ifPresent(r -> {
            if (!r.getQuotation().getId().equals(quotationId)) return;
            r.setStatus(com.oorja.backend.entity.enums.RevisionRequestStatus.RESOLVED);
            r.setResolutionNote(note);
            r.setResolvedAt(LocalDateTime.now());
            r.setResolvedBy(adminId);
            revisionRequestRepository.save(r);
        });
    }

    // ---------- Shared helpers ----------

    private QuotationResponse toResponse(Quotation quotation) {
        List<BookingAttachmentResponse> attachments = bookingAttachmentRepository
                .findByBookingIdAndDeletedAtIsNull(quotation.getBooking().getId()).stream()
                .map(BookingAttachmentResponse::from)
                .toList();
        return QuotationResponse.from(quotation, attachments);
    }

    private boolean paidAmountMatches(Quotation quotation, Payment payment) {
        if (quotation.getTotalAmount() == null) {
            return false;
        }
        long expectedPaise = quotation.getTotalAmount()
                .multiply(java.math.BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .longValue();
        return payment.getAmountPaise() != null && payment.getAmountPaise() == expectedPaise;
    }

    private void assertPaidAmountMatchesQuotation(Quotation quotation, Payment payment) {
        if (!paidAmountMatches(quotation, payment)) {
            throw ApiException.conflict("The amount paid doesn't match the current quotation price. "
                    + "Please contact support - your payment is safe.", "AMOUNT_MISMATCH");
        }
    }

    Quotation getOrThrow(Long id) {
        return quotationRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Quotation not found: " + id));
    }

    private Quotation getOwnedByOrThrow(Long id, Long customerId) {
        Quotation quotation = getOrThrow(id);
        if (!quotation.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("This quotation does not belong to your account.");
        }
        return quotation;
    }

    /** Row-locking variant of {@link #getOwnedByOrThrow}, for status-changing customer actions. */
    private Quotation getOwnedByOrThrowForUpdate(Long id, Long customerId) {
        Quotation quotation = quotationRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("Quotation not found: " + id));
        if (!quotation.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("This quotation does not belong to your account.");
        }
        return quotation;
    }
}
