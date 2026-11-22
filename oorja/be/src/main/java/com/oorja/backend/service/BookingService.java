package com.oorja.backend.service;

import com.oorja.backend.dto.request.CancelBookingRequest;
import com.oorja.backend.dto.request.CreateBookingRequest;
import com.oorja.backend.dto.request.UpdateBookingRequest;
import com.oorja.backend.dto.response.BookingAttachmentResponse;
import com.oorja.backend.dto.response.BookingResponse;
import com.oorja.backend.dto.response.CancellationPreviewResponse;
import com.oorja.backend.dto.response.SlotAvailabilityResponse;
import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.BookingAttachment;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.Payment;
import com.oorja.backend.entity.Plant;
import com.oorja.backend.entity.Technician;
import com.oorja.backend.entity.Refund;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.entity.enums.RefundReasonCode;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingAttachmentRepository;
import com.oorja.backend.repository.BookingRepository;
import com.oorja.backend.repository.CustomerRepository;
import com.oorja.backend.repository.PlantRepository;
import com.oorja.backend.repository.TechnicianRepository;
import com.oorja.backend.storage.StorageKeys;
import com.oorja.backend.storage.StorageProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final CustomerRepository customerRepository;
    private final PlantRepository plantRepository;
    private final TechnicianRepository technicianRepository;
    private final BookingAttachmentRepository bookingAttachmentRepository;
    private final CodeGeneratorService codeGeneratorService;
    private final PlantService plantService;
    private final StorageProvider storageProvider;
    private final QuotationService quotationService;
    private final RefundService refundService;
    private final PaymentService paymentService;
    private final SlotService slotService;
    private final com.oorja.backend.repository.RefundRepository refundRepository;

    /** Mirrors refund.policy.settlement-days so the preview can promise the same window the refund does. */
    @org.springframework.beans.factory.annotation.Value("${refund.policy.settlement-days:7}")
    private int refundSettlementDays;

    // ---------- Admin: view everything ----------

    /** Paginated for the admin console list — an earlier unpaginated version loaded the whole table into memory. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<BookingResponse> findAll(org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<Booking> bookings = bookingRepository.findAll(pageable);
        Map<Long, List<BookingAttachmentResponse>> attachmentsByBooking = attachmentsFor(bookings.getContent());
        return bookings.map(b -> BookingResponse.from(b, attachmentsByBooking.getOrDefault(b.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public BookingResponse findById(Long id) {
        return withAttachments(getOrThrow(id));
    }

    // ---------- Customer: create + view own bookings ----------

    @Transactional
    public BookingResponse createForCustomer(Long customerId, CreateBookingRequest request) {
        return createForCustomer(customerId, request, null);
    }

    /**
     * AMC subscription quote requests don't need a technician visit date up
     * front (that's arranged once the quote is accepted and paid), so this
     * skips the date/time picker entirely and creates a quote-request
     * booking directly - it flows through the exact same admin pricing /
     * customer accept-and-pay quotation workflow as an installation quote.
     */
    @Transactional
    public BookingResponse requestAmcQuote(Long customerId, Long plantId, String planLabel, String notes) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> ApiException.notFound("Customer not found"));
        Plant plant = plantRepository.findById(plantId)
                .orElseThrow(() -> ApiException.notFound("Plant not found"));
        if (plant.getCustomer() == null || !plant.getCustomer().getId().equals(customerId)) {
            throw ApiException.badRequest("This plant is not registered under your account.");
        }
        if (plant.isDeleted()) {
            throw ApiException.badRequest("This plant has been removed from your account. Please pick another site.");
        }

        Booking booking = Booking.builder()
                .token(codeGeneratorService.nextBookingToken())
                .customer(customer)
                .plant(plant)
                .serviceType("AMC Subscription — " + planLabel)
                .scheduledDate(java.time.LocalDate.now())
                .scheduledTime(java.time.LocalTime.of(9, 0))
                .status(BookingStatus.PENDING)
                .notes(notes)
                .location(plant.getLocation())
                .build();

        booking = bookingRepository.save(booking);
        quotationService.createDraftForBooking(booking);
        return BookingResponse.from(booking, List.of());
    }

    /**
     * Called only from the Razorpay webhook, when a payment came back
     * CAPTURED but the customer's own POST /bookings call never arrived
     * (browser closed, app killed, etc. right after paying). Builds the
     * booking straight from the draft fields stored on the Payment row at
     * create-order time — see PaymentWebhookService for the caller, which
     * holds a row lock on the Payment so this can't race the customer's own
     * request and create two bookings for one payment.
     */
    @Transactional
    public void createFromCapturedPayment(Payment payment) {
        if (payment.getDraftPlantId() == null || payment.getDraftScheduledDate() == null
                || payment.getDraftScheduledTime() == null) {
            // Not enough information to safely build a booking (e.g. an older
            // payment created before draft fields existed, or a quotation
            // payment, which is handled separately) - leave for manual reconciliation.
            return;
        }

        Plant plant = plantRepository.findById(payment.getDraftPlantId()).orElse(null);
        if (plant == null) {
            return;
        }

        // Money is already captured at this point, so this can never refuse to create the booking. Normally the
        // seat was claimed at checkout and is simply handed over; only an order whose hold lapsed before it was
        // paid falls back to a best-effort claim (see claimSeatForPaidBooking).
        boolean slotReserved = claimSeatForPaidBooking(payment, payment.getDraftScheduledDate(), payment.getDraftScheduledTime());

        Booking booking = Booking.builder()
                .token(codeGeneratorService.nextBookingToken())
                .customer(payment.getCustomer())
                .plant(plant)
                .serviceType(payment.getDraftServiceType() != null ? payment.getDraftServiceType() : payment.getServiceId())
                .scheduledDate(payment.getDraftScheduledDate())
                .scheduledTime(payment.getDraftScheduledTime())
                .status(BookingStatus.PENDING)
                .notes(payment.getDraftNotes())
                .location(payment.getDraftLocation())
                .amountPaise(payment.getAmountPaise())
                .paymentRef(payment.getRazorpayPaymentId())
                .slotReserved(slotReserved)
                .build();

        booking = bookingRepository.save(booking);
        paymentService.linkToBooking(payment, booking);
    }

    /**
     * Gives a PAID booking the seat it is entitled to.
     * <ol>
     *   <li>Normal case: the seat was claimed when the order was created ("hold") - the booking takes it over,
     *       so no second claim is made and capacity is not double-counted.</li>
     *   <li>No hold (the order predates holds, or an unpaid hold lapsed before the customer finished paying):
     *       claim a seat now. If the slot filled up in the meantime the booking still has to exist - the money
     *       is already taken - so it is created over capacity and logged at ERROR for ops to re-slot or refund.
     *       This is the single, deliberate exception to "never exceed capacity".</li>
     * </ol>
     *
     * @return whether the booking now owns a seat in booking_slots
     */
    private boolean claimSeatForPaidBooking(Payment payment, java.time.LocalDate date, java.time.LocalTime time) {
        if (paymentService.takeOverSlotHold(payment, date, time)) {
            return true;
        }
        if (!slotService.isOnGrid(time)) {
            return false;
        }
        slotService.reserveBestEffort(date, time);
        return true;
    }

    @Transactional
    public BookingResponse createForCustomer(Long customerId, CreateBookingRequest request, List<MultipartFile> files) {
        return createForCustomer(customerId, request, files, null);
    }

    /**
     * Returns the booking this customer already created under the given
     * Idempotency-Key, if any. Used by the controller to resolve the loser of
     * a concurrent duplicate request (its insert hit uq_bookings_customer_idem).
     */
    @Transactional(readOnly = true)
    public java.util.Optional<BookingResponse> findByIdempotencyKey(Long customerId, String idempotencyKey) {
        if (idempotencyKey == null) {
            return java.util.Optional.empty();
        }
        return bookingRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                .map(this::withAttachments);
    }

    /**
     * Same as above, but also stores any uploaded site photos/documents against the new booking.
     * <p>
     * With a non-null {@code idempotencyKey}, a retry of an already-completed request returns
     * the original booking instead of creating a second one (and consuming a second slot seat).
     * Concurrent duplicates are stopped by the unique index and resolved by the controller.
     */
    @Transactional
    public BookingResponse createForCustomer(Long customerId, CreateBookingRequest request, List<MultipartFile> files,
                                             String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = bookingRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (existing.isPresent()) {
                return withAttachments(existing.get());
            }
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> ApiException.notFound("Customer not found"));

        // If this booking is for something the customer just paid for, the amount/payment reference are copied
        // from the CAPTURED Payment row that create-order + verify produced - never from the request body.
        // The payment row is locked here, so two requests racing to spend the same payment (double-click, second
        // tab, client retry racing the webhook) are serialized: exactly one creates the booking.
        Payment payment = null;
        Long amountPaise = null;
        String paymentRef = null;
        if (request.razorpayOrderId() != null && !request.razorpayOrderId().isBlank()) {
            payment = paymentService.consumeCapturedPayment(customerId, request.razorpayOrderId());
            if (payment.getBooking() != null) {
                // Already spent: either the customer's own earlier request succeeded and this is a retry after a
                // lost response, or the Razorpay webhook created the booking first. Either way the right answer
                // is that booking - NOT an error, and NOT a second booking for the same payment.
                return withAttachments(payment.getBooking());
            }
            amountPaise = payment.getAmountPaise();
            paymentRef = payment.getRazorpayPaymentId();
        }

        Plant plant;
        if (request.plantId() != null) {
            plant = plantRepository.findById(request.plantId())
                    .orElseThrow(() -> ApiException.notFound("Plant not found"));
            if (plant.getCustomer() != null && !plant.getCustomer().getId().equals(customerId)) {
                throw ApiException.forbidden("This plant is not registered under your account.");
            }
            if (plant.isDeleted()) {
                throw ApiException.badRequest("This plant has been removed from your account. Please pick another site.");
            }
        } else {
            // Storefront checkout doesn't collect a plant explicitly - reuse the
            // customer's existing plant, or provision a starter one.
            plant = plantService.findOrCreateDefaultForCustomer(customer, request.location());
        }

        // Seat. Unpaid request: strict - a full slot rejects the request. Paid request: the seat claimed at
        // checkout is handed over (see claimSeatForPaidBooking); the capacity guarantee was enforced then,
        // before any money moved.
        boolean slotReserved;
        if (payment != null) {
            slotReserved = claimSeatForPaidBooking(payment, request.scheduledDate(), request.scheduledTime());
        } else {
            slotService.reserve(request.scheduledDate(), request.scheduledTime());
            slotReserved = slotService.isOnGrid(request.scheduledTime());
        }

        Booking booking = Booking.builder()
                .token(codeGeneratorService.nextBookingToken())
                .customer(customer)
                .plant(plant)
                .serviceType(request.serviceType())
                .scheduledDate(request.scheduledDate())
                .scheduledTime(request.scheduledTime())
                .status(BookingStatus.PENDING)
                .notes(request.notes())
                .location(request.location())
                .amountPaise(amountPaise)
                .paymentRef(paymentRef)
                .serviceLabel(request.serviceLabel())
                .slotReserved(slotReserved)
                .idempotencyKey(idempotencyKey)
                .build();

        booking = bookingRepository.save(booking);

        if (payment != null) {
            paymentService.linkToBooking(payment, booking);
        }

        List<BookingAttachmentResponse> attachmentResponses = List.of();
        if (files != null && !files.isEmpty()) {
            attachmentResponses = storeAttachments(booking, files, customer.getId(), SubjectType.CUSTOMER, false);
        }

        if (Boolean.TRUE.equals(request.requestQuote())) {
            quotationService.createDraftForBooking(booking);
        }

        return BookingResponse.from(booking, attachmentResponses);
    }

    /**
     * Adds one or more files to a booking the customer already has — the
     * "+ Upload File" action inside booking details, for docs/photos that
     * weren't attached (or weren't ready) at booking time. Ownership is
     * checked the same way as every other customer-scoped booking action.
     */
    @Transactional
    public BookingResponse addAttachmentsForCustomer(Long customerId, Long bookingId, List<MultipartFile> files) {
        Booking booking = getOrThrow(bookingId);
        if (!booking.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("This booking doesn't belong to your account.");
        }
        if (files == null || files.stream().allMatch(f -> f == null || f.isEmpty())) {
            throw ApiException.badRequest("Please choose at least one file to upload.");
        }
        storeAttachments(booking, files, customerId, SubjectType.CUSTOMER, false);
        return withAttachments(booking);
    }

    /**
     * Hands uploaded files to the configured storage provider (local disk in
     * dev, S3/R2 in production) and records the resulting keys against the
     * booking. Only metadata is persisted — never the bytes.
     */
    private List<BookingAttachmentResponse> storeAttachments(Booking booking, List<MultipartFile> files,
                                                              Long uploadedBy, SubjectType uploadedByType,
                                                              boolean officialDocument) {
        String keyPrefix = StorageKeys.bookingPrefix(booking);
        return files.stream()
                .filter(f -> f != null && !f.isEmpty())
                .map(f -> {
                    String storageKey = storageProvider.store(f, keyPrefix);
                    BookingAttachment attachment = BookingAttachment.builder()
                            .booking(booking)
                            .fileName(f.getOriginalFilename() != null ? f.getOriginalFilename() : "file")
                            .contentType(f.getContentType())
                            .sizeBytes(f.getSize())
                            .storageKey(storageKey)
                            .uploadedBy(uploadedBy)
                            .uploadedByType(uploadedByType)
                            .officialDocument(officialDocument)
                            .build();
                    return BookingAttachmentResponse.from(bookingAttachmentRepository.save(attachment));
                })
                .collect(Collectors.toList());
    }

    private Map<Long, List<BookingAttachmentResponse>> attachmentsFor(List<Booking> bookings) {
        if (bookings.isEmpty()) return Collections.emptyMap();
        List<Long> ids = bookings.stream().map(Booking::getId).toList();
        return bookingAttachmentRepository.findByBookingIdInAndDeletedAtIsNull(ids).stream()
                .collect(Collectors.groupingBy(
                        a -> a.getBooking().getId(),
                        Collectors.mapping(BookingAttachmentResponse::from, Collectors.toList())
                ));
    }

    private BookingResponse withAttachments(Booking booking) {
        List<BookingAttachmentResponse> attachments = bookingAttachmentRepository.findByBookingIdAndDeletedAtIsNull(booking.getId())
                .stream().map(BookingAttachmentResponse::from).toList();
        return BookingResponse.from(booking, attachments);
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> findByCustomer(Long customerId) {
        List<Booking> bookings = bookingRepository.findByCustomerIdOrderByCreatedAtDesc(customerId);
        Map<Long, List<BookingAttachmentResponse>> attachmentsByBooking = attachmentsFor(bookings);
        return bookings.stream()
                .map(b -> BookingResponse.from(b, attachmentsByBooking.getOrDefault(b.getId(), List.of())))
                .toList();
    }

    /**
     * What the customer is shown before they commit to cancelling: the exact
     * refund they'd get, and the rebooking alternative. Read-only — nothing
     * is cancelled, nothing is reserved.
     */
    @Transactional(readOnly = true)
    public CancellationPreviewResponse previewCancellation(Long customerId, Long bookingId, RefundReasonCode reasonCode) {
        Booking booking = getOrThrow(bookingId);
        if (!booking.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("You cannot cancel someone else's booking.");
        }

        RefundService.Quote quote = refundService.preview(booking,
                reasonCode == null ? RefundReasonCode.CUSTOMER_REQUEST : reasonCode);
        var decision = quote.decision();
        var breakdown = quote.breakdown();

        boolean rebookable = booking.getStatus() == BookingStatus.PENDING
                || booking.getStatus() == BookingStatus.ASSIGNED;
        long paid = booking.getAmountPaise() == null ? 0L : booking.getAmountPaise();
        long keptByRebooking = paid;

        List<CancellationPreviewResponse.SlotSuggestion> suggestions = rebookable
                ? suggestSlots(3)
                : List.of();

        String rebookMessage = null;
        if (rebookable && paid > 0) {
            long lostByCancelling = paid - breakdown.netPaise();
            rebookMessage = lostByCancelling > 0
                    ? String.format("Move it instead and you keep the whole \u20b9%,.0f \u2014 cancelling costs you \u20b9%,.0f.",
                        paid / 100.0, lostByCancelling / 100.0)
                    : "Prefer a different day? Moving it keeps your slot and your payment as they are.";
        }

        return CancellationPreviewResponse.builder()
                .bookingId(booking.getId())
                .bookingToken(booking.getToken())
                .serviceLabel(booking.getServiceLabel() != null ? booking.getServiceLabel() : booking.getServiceType())
                .scheduledDate(booking.getScheduledDate().toString())
                .scheduledTime(booking.getScheduledTime().toString())
                .refundable(decision.eligible() && breakdown.netPaise() > 0)
                .policyTier(decision.tier().name())
                .message(decision.message())
                .paidInr(paid / 100.0)
                .refundPercent(breakdown.percentBps() / 100)
                .cancellationFeeInr(breakdown.cancellationFeePaise() / 100.0)
                .gatewayFeeInr(breakdown.gatewayFeePaise() / 100.0)
                .deductionsInr(Math.max(0L, paid - breakdown.netPaise()) / 100.0)
                .refundAmountInr(breakdown.netPaise() / 100.0)
                .autoApproved(decision.autoApprovable())
                .settlementDays(refundSettlementDays)
                .rebookAvailable(rebookable)
                .rebookRecommended(rebookable && decision.rebookingPreferred())
                .rebookValueInr(keptByRebooking / 100.0)
                .rebookMessage(rebookMessage)
                .suggestedSlots(suggestions)
                .build();
    }

    /** The next few genuinely bookable slots, so rebooking is one tap rather than a new journey. */
    private List<CancellationPreviewResponse.SlotSuggestion> suggestSlots(int limit) {
        List<CancellationPreviewResponse.SlotSuggestion> out = new java.util.ArrayList<>();
        java.time.LocalDate day = java.time.LocalDate.now();
        for (int d = 0; d < 7 && out.size() < limit; d++) {
            java.time.LocalDate probe = day.plusDays(d);
            for (SlotAvailabilityResponse slot : slotService.listAvailability(probe)) {
                if (!slot.available()) continue;
                out.add(CancellationPreviewResponse.SlotSuggestion.builder()
                        .date(probe.toString())
                        .time(slot.time().toString())
                        .label(slot.label())
                        .seatsLeft(slot.remaining())
                        .build());
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    /**
     * Cancels a booking, or — when the customer picked the rebooking option
     * in the preview — moves it instead and never creates a refund at all.
     *
     * The refund is raised inside this transaction: if the cancellation
     * rolls back, so does the money owed. The gateway call happens after
     * commit (see RefundDispatchService), never inside it.
     */
    @Transactional
    public CancellationOutcome cancelForCustomer(Long customerId, Long bookingId, CancelBookingRequest request) {
        Booking booking = getOrThrowForUpdate(bookingId);
        if (!booking.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("You cannot cancel someone else's booking.");
        }

        CancelBookingRequest req = request == null
                ? new CancelBookingRequest(null, null, null, null, null)
                : request;

        // Rebooking branch: the booking survives, the payment stays put.
        if (req.intentOrDefault() == CancelBookingRequest.Intent.REBOOK) {
            if (req.newDate() == null || req.newTime() == null) {
                throw ApiException.badRequest("Choose a new date and time to move this booking to.");
            }
            BookingResponse moved = rescheduleForCustomer(customerId, bookingId, req.newDate(), req.newTime());
            return new CancellationOutcome(moved, null, true);
        }

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            // Retry after a lost response, or a double-click: cancelling twice must not fail, and must never raise a
            // second refund. Answer with the original outcome.
            Long existingRefundId = refundRepository.findByBookingIdOrderByRequestedAtDesc(bookingId).stream()
                    .findFirst().map(Refund::getId).orElse(null);
            return new CancellationOutcome(withAttachments(booking), existingRefundId, false);
        }
        requireTransition(booking, BookingStatus.CANCELLED, "cancelled");

        booking.setStatus(BookingStatus.CANCELLED);
        releaseSlotIfHeld(booking);
        bookingRepository.save(booking);

        String reason = (req.reason() != null && !req.reason().isBlank()) ? req.reason() : "Cancelled by customer";
        Refund refund = refundService.requestForCancelledBooking(
                booking, req.reasonCodeOrDefault(), reason, SubjectType.CUSTOMER, customerId, "customer");

        return new CancellationOutcome(withAttachments(booking), refund == null ? null : refund.getId(), false);
    }

    /** Legacy two-argument form, kept so existing callers and tests don't break. */
    @Transactional
    public void cancelForCustomer(Long customerId, Long bookingId, String reason) {
        cancelForCustomer(customerId, bookingId, new CancelBookingRequest(reason, null, null, null, null));
    }

    /**
     * @param booking   the booking as it now stands
     * @param refundId  the refund raised, if any
     * @param rebooked  true when the customer moved the booking instead of cancelling
     */
    public record CancellationOutcome(BookingResponse booking, Long refundId, boolean rebooked) {}

    /**
     * Brings a cancelled booking back to life at a new slot, withdrawing the
     * pending refund in the same transaction. This is the "changed my mind,
     * book me in instead" path — the customer keeps 100% of what they paid
     * rather than waiting a week for a refund and paying again.
     */
    @Transactional
    public BookingResponse rebookFromRefund(Long customerId, Long refundId,
                                            java.time.LocalDate date, java.time.LocalTime time) {
        // Lock order is booking -> refund everywhere (cancelForCustomer does the same), so a rebook racing a
        // cancel/approve on the same booking can't deadlock.
        Long bookingId = refundRepository.findById(refundId)
                .orElseThrow(() -> ApiException.notFound("Refund not found: " + refundId))
                .getBooking().getId();
        Booking booking = getOrThrowForUpdate(bookingId);
        if (!booking.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("You cannot rebook someone else's booking.");
        }
        if (booking.getStatus() != BookingStatus.CANCELLED) {
            throw ApiException.invalidState("Only a cancelled booking can be revived from its refund.");
        }
        if (date.isBefore(java.time.LocalDate.now())) {
            throw ApiException.badRequest("Please choose a date in the future.");
        }

        Refund refund = refundService.withdrawForRebooking(refundId, customerId,
                "Customer rebooked this service instead of taking the refund.");

        // Claim the new seat first: if it's full, nothing above is committed
        // and the refund stays exactly as it was.
        slotService.reserve(date, time);
        booking.setSlotReserved(true);
        booking.setScheduledDate(date);
        booking.setScheduledTime(time);
        booking.setStatus(BookingStatus.PENDING);
        booking.setTechnician(null);

        return withAttachments(bookingRepository.save(booking));
    }

    @Transactional
    public BookingResponse rescheduleForCustomer(Long customerId, Long bookingId, java.time.LocalDate date, java.time.LocalTime time) {
        Booking booking = getOrThrowForUpdate(bookingId);
        if (!booking.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("You cannot reschedule someone else's booking.");
        }
        if (booking.getStatus() != BookingStatus.PENDING && booking.getStatus() != BookingStatus.ASSIGNED) {
            throw ApiException.invalidState("Only a pending or assigned booking can be rescheduled.");
        }
        if (date.isBefore(java.time.LocalDate.now())) {
            throw ApiException.badRequest("Please choose a date in the future.");
        }

        java.time.LocalDate oldDate = booking.getScheduledDate();
        java.time.LocalTime oldTime = booking.getScheduledTime();
        boolean oldReserved = booking.isSlotReserved();

        boolean unchanged = date.equals(oldDate) && time.equals(oldTime);
        if (!unchanged) {
            // Claim the new slot before touching anything else - if it's
            // full or invalid this throws and the booking is left
            // completely unchanged, still holding its original slot.
            slotService.reserve(date, time);
            booking.setSlotReserved(true);
            if (oldReserved) {
                slotService.release(oldDate, oldTime);
            }
        }

        booking.setScheduledDate(date);
        booking.setScheduledTime(time);

        return withAttachments(bookingRepository.save(booking));
    }

    // ---------- Admin: manage lifecycle ----------

    /** Assigns (or reassigns) a technician to a booking, moving it to ASSIGNED if it was still PENDING. */
    @Transactional
    public BookingResponse assignTechnician(Long bookingId, Long technicianId) {
        Booking booking = getOrThrowForUpdate(bookingId);
        Technician technician = technicianRepository.findById(technicianId)
                .orElseThrow(() -> ApiException.notFound("Technician not found"));
        if (!booking.getStatus().isOpen()) {
            throw ApiException.invalidState("A " + booking.getStatus().name().toLowerCase()
                    + " booking can't be assigned a technician.");
        }

        booking.setTechnician(technician);

        if (booking.getStatus() == BookingStatus.PENDING) {
            booking.setStatus(BookingStatus.ASSIGNED);
        }

        return withAttachments(bookingRepository.save(booking));
    }

    /** Marks a booking as done on-site — flips it to Completed immediately in the customer's app. */
    @Transactional
    public BookingResponse markCompleted(Long bookingId, String notes) {
        Booking booking = getOrThrowForUpdate(bookingId);
        if (booking.getStatus() == BookingStatus.COMPLETED) {
            return withAttachments(booking); // double-click / retry: already done, nothing to change
        }
        requireTransition(booking, BookingStatus.COMPLETED, "marked complete");
        if (notes != null && !notes.isBlank()) {
            booking.setNotes(notes);
        }
        booking.setStatus(BookingStatus.COMPLETED);
        return withAttachments(bookingRepository.save(booking));
    }

    /** Admin-initiated cancellation, e.g. because the customer called in or the site fell through. */
    @Transactional
    public BookingResponse cancelByAdmin(Long bookingId, String reason) {
        Booking booking = getOrThrowForUpdate(bookingId);
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return withAttachments(booking); // double-click / retry: already cancelled, and the refund already exists
        }
        requireTransition(booking, BookingStatus.CANCELLED, "cancelled");
        if (reason != null && !reason.isBlank()) {
            String prefixed = "Cancelled: " + reason;
            booking.setNotes(booking.getNotes() == null || booking.getNotes().isBlank()
                    ? prefixed
                    : booking.getNotes() + "\n" + prefixed);
        }
        booking.setStatus(BookingStatus.CANCELLED);
        releaseSlotIfHeld(booking);
        BookingResponse response = withAttachments(bookingRepository.save(booking));
        // An admin cancelling is, from the customer's point of view, us
        // cancelling on them — so it's a provider-fault refund: always full,
        // always auto-approved, no policy deductions.
        refundService.requestForCancelledBooking(booking, RefundReasonCode.PROVIDER_CANCELLED,
                (reason != null && !reason.isBlank()) ? reason : "Cancelled by our team",
                SubjectType.ADMIN, null, "admin console");
        return response;
    }

    @Transactional
    public BookingResponse update(Long id, UpdateBookingRequest request) {
        Booking booking = getOrThrowForUpdate(id);

        if (request.scheduledDate() != null || request.scheduledTime() != null) {
            java.time.LocalDate newDate = request.scheduledDate() != null ? request.scheduledDate() : booking.getScheduledDate();
            java.time.LocalTime newTime = request.scheduledTime() != null ? request.scheduledTime() : booking.getScheduledTime();

            if (!newDate.equals(booking.getScheduledDate()) || !newTime.equals(booking.getScheduledTime())) {
                java.time.LocalDate oldDate = booking.getScheduledDate();
                java.time.LocalTime oldTime = booking.getScheduledTime();
                boolean oldReserved = booking.isSlotReserved();

                // Admin edits never get blocked by a full slot, but still
                // keep the slot counters accurate for everyone else.
                slotService.reserveBestEffort(newDate, newTime);
                booking.setScheduledDate(newDate);
                booking.setScheduledTime(newTime);
                booking.setSlotReserved(slotService.isOnGrid(newTime));

                if (oldReserved) {
                    slotService.release(oldDate, oldTime);
                }
            }
        }
        if (request.notes() != null) booking.setNotes(request.notes());

        if (request.technicianId() != null) {
            Technician technician = technicianRepository.findById(request.technicianId())
                    .orElseThrow(() -> ApiException.notFound("Technician not found"));
            booking.setTechnician(technician);
            if (booking.getStatus() == BookingStatus.PENDING) {
                booking.setStatus(BookingStatus.ASSIGNED);
            }
        }

        if (request.status() != null && request.status() != booking.getStatus()) {
            if (request.status() == BookingStatus.CANCELLED || request.status() == BookingStatus.PENDING) {
                // Cancelling releases the seat and raises the refund; reviving goes through rebook-from-refund.
                // A bare status edit would skip both and leave the slot counter and the books wrong.
                throw ApiException.invalidState("Use the cancel action to cancel a booking; a cancelled booking is "
                        + "revived through its refund, not by editing its status.");
            }
            requireTransition(booking, request.status(), "moved to " + request.status().name().toLowerCase());
            booking.setStatus(request.status());
        }

        return BookingResponse.from(bookingRepository.save(booking));
    }

    @Transactional
    public void delete(Long id) {
        Booking booking = getOrThrowForUpdate(id);
        boolean hasMoney = booking.getPaymentRef() != null
                || (booking.getAmountPaise() != null && booking.getAmountPaise() > 0)
                || !refundRepository.findByBookingIdOrderByRequestedAtDesc(id).isEmpty();
        if (hasMoney || booking.getStatus() == BookingStatus.COMPLETED) {
            // refunds.booking_id is ON DELETE CASCADE: deleting here would silently erase the refund and ledger trail.
            throw ApiException.invalidState("This booking has payment, refund or service records and can't be deleted. "
                    + "Cancel it instead.");
        }
        releaseSlotIfHeld(booking);
        bookingRepository.delete(booking);
    }

    /** Single enforcement point for {@link BookingStatus#canTransitionTo}: illegal moves are a 409, never a silent write. */
    private void requireTransition(Booking booking, BookingStatus next, String verb) {
        if (!booking.getStatus().canTransitionTo(next)) {
            throw ApiException.invalidState("A " + booking.getStatus().name().toLowerCase().replace('_', ' ')
                    + " booking can't be " + verb + ".");
        }
    }

    private Booking getOrThrow(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Booking not found: " + id));
    }

    /**
     * Row-locking variant of {@link #getOrThrow}, used by every action that
     * reads a booking's status/slot then writes a decision based on it
     * (cancel, complete, reschedule, assign, admin edits, delete). Without
     * this, two concurrent requests against the same booking (e.g. a
     * cancellation racing a completion) can both read the pre-change status,
     * both pass their guard, and both commit.
     */
    private Booking getOrThrowForUpdate(Long id) {
        return bookingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> ApiException.notFound("Booking not found: " + id));
    }

    /** Frees this booking's held seat (if any) in booking_slots and clears the flag so it's never released twice. */
    private void releaseSlotIfHeld(Booking booking) {
        if (booking.isSlotReserved()) {
            slotService.release(booking.getScheduledDate(), booking.getScheduledTime());
            booking.setSlotReserved(false);
        }
    }
}
