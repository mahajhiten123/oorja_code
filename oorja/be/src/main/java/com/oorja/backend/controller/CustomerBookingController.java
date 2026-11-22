package com.oorja.backend.controller;

import com.oorja.backend.dto.request.CancelBookingRequest;
import com.oorja.backend.dto.request.CreateBookingRequest;
import com.oorja.backend.dto.request.PriceQuoteRequest;
import com.oorja.backend.dto.request.RescheduleBookingRequest;
import com.oorja.backend.dto.response.ApiMessageResponse;
import com.oorja.backend.dto.response.BookingResponse;
import com.oorja.backend.dto.response.CancellationPreviewResponse;
import com.oorja.backend.dto.response.PriceQuoteResponse;
import com.oorja.backend.dto.response.SlotAvailabilityResponse;
import com.oorja.backend.entity.BookingAttachment;
import com.oorja.backend.entity.enums.RefundReasonCode;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.BookingService;
import com.oorja.backend.service.AttachmentService;
import com.oorja.backend.storage.StorageProvider;
import com.oorja.backend.service.PaymentService;
import com.oorja.backend.service.SlotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/customer/bookings")
@RequiredArgsConstructor
public class CustomerBookingController {

    private final BookingService bookingService;
    private final ObjectMapper objectMapper;
    private final jakarta.validation.Validator validator;
    private final StorageProvider storageProvider;
    private final AttachmentService attachmentService;
    private final PaymentService paymentService;
    private final SlotService slotService;

    /**
     * The published slot grid for a date, with live remaining-capacity per
     * slot — the storefront's date/time picker renders exactly this instead
     * of a hardcoded list, so a full or too-soon slot is disabled before the
     * customer ever tries to book it.
     */
    @GetMapping("/slots")
    public List<SlotAvailabilityResponse> slots(@RequestParam("date") LocalDate date) {
        return slotService.listAvailability(date);
    }

    /**
     * Backend-authoritative price for a service (+ optional cleaning tier /
     * coupon) before the customer commits to paying — Checkout displays
     * this instead of computing a total in the browser.
     */
    @PostMapping("/price")
    public PriceQuoteResponse price(@Valid @RequestBody PriceQuoteRequest request) {
        return paymentService.quote(request);
    }

    @GetMapping
    public List<BookingResponse> myBookings() {
        return bookingService.findByCustomer(CurrentUser.get().userId());
    }

    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @Valid @RequestBody CreateBookingRequest request,
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        return ResponseEntity.ok(createIdempotent(request, null, idempotencyKey));
    }

    /**
     * Creates the booking, treating a duplicate Idempotency-Key as "return the original".
     * The sequential-retry case is handled inside the service; here we resolve the
     * concurrent case, where the second insert loses on the unique index. Postgres makes
     * that insert wait for the first transaction, so by the time we catch the violation
     * the winner has committed and can be read back.
     */
    private BookingResponse createIdempotent(CreateBookingRequest request, List<MultipartFile> files, String rawKey) {
        Long customerId = CurrentUser.get().userId();
        String key = IdempotencyKeys.normalize(rawKey);
        try {
            return bookingService.createForCustomer(customerId, request, files, key);
        } catch (DataIntegrityViolationException e) {
            return bookingService.findByIdempotencyKey(customerId, key).orElseThrow(() -> e);
        }
    }

    /**
     * Same as above, but for flows that also collect site photos/documents
     * (e.g. requesting an installation quote) — accepts the booking as a
     * JSON "data" part alongside one or more "files" parts.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BookingResponse> createWithFiles(
            @RequestPart("data") String data,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestHeader(value = IdempotencyKeys.HEADER, required = false) String idempotencyKey) {
        CreateBookingRequest request;
        try {
            request = objectMapper.readValue(data, CreateBookingRequest.class);
        } catch (Exception e) {
            // Don't echo the parser's message: it can contain internal class names and raw input.
            throw ApiException.badRequest("The booking data is malformed. Please check the fields and try again.");
        }
        // The JSON arrives as a plain string part, so Spring's @Valid never sees it - run the same
        // Bean Validation rules by hand, or this endpoint would accept what the JSON endpoint rejects.
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new jakarta.validation.ConstraintViolationException(violations);
        }
        return ResponseEntity.ok(createIdempotent(request, files, idempotencyKey));
    }

    /**
     * Adds site photos/documents to a booking the customer already has —
     * the "+ Upload File" action inside booking details, for files that
     * weren't (or couldn't be) attached at booking time.
     */
    @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BookingResponse addAttachments(@PathVariable Long id, @RequestPart("files") List<MultipartFile> files) {
        return bookingService.addAttachmentsForCustomer(CurrentUser.get().userId(), id, files);
    }

    /**
     * Stage 1–3, without committing to anything: what would happen if I
     * cancelled this? Returns the exact refund figure, the policy reason in
     * plain words, and the rebooking alternative with real open slots.
     *
     * The app calls this the moment the customer taps Cancel, so the choice
     * they make is an informed one rather than a surprise a week later.
     */
    @GetMapping("/{id}/cancellation-preview")
    public CancellationPreviewResponse cancellationPreview(@PathVariable Long id,
                                                           @RequestParam(required = false) RefundReasonCode reasonCode) {
        return bookingService.previewCancellation(CurrentUser.get().userId(), id, reasonCode);
    }

    /**
     * Cancel — or, if the customer picked "move it instead" in the preview,
     * reschedule and raise no refund at all. One endpoint because it's one
     * decision from the customer's side.
     */
    @PostMapping("/{id}/cancel")
    public BookingService.CancellationOutcome cancel(@PathVariable Long id,
                                                     @Valid @RequestBody(required = false) CancelBookingRequest request) {
        return bookingService.cancelForCustomer(CurrentUser.get().userId(), id, request);
    }

    @PostMapping("/{id}/reschedule")
    public BookingResponse reschedule(@PathVariable Long id, @Valid @RequestBody RescheduleBookingRequest request) {
        return bookingService.rescheduleForCustomer(CurrentUser.get().userId(), id, request.scheduledDate(), request.scheduledTime());
    }

    /**
     * Streams a booking attachment (site photo, completion/service report
     * photo, etc.) back to the customer who owns the booking it belongs
     * to — e.g. from My Plants → Service history / Documents.
     */
    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable Long attachmentId) {
        BookingAttachment attachment = attachmentService.getForCustomer(attachmentId, CurrentUser.get().userId());
        Resource resource = storageProvider.load(attachment.getStorageKey());

        MediaType mediaType = attachment.getContentType() != null
                ? MediaType.parseMediaType(attachment.getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + attachment.getFileName() + "\"")
                .body(resource);
    }

    /**
     * Deletes a file the customer uploaded themselves.
     *
     * Allowed only while the booking is still open and only for their own
     * uploads — service reports and completion documents produced by ops are
     * part of the service record and can't be removed here. The delete is soft:
     * the row is kept as an audit record and the object is destroyed later by
     * the nightly purge job.
     */
    @DeleteMapping("/attachments/{attachmentId}")
    public ResponseEntity<ApiMessageResponse> deleteAttachment(@PathVariable Long attachmentId) {
        attachmentService.deleteAsCustomer(attachmentId, CurrentUser.get().userId());
        return ResponseEntity.ok(ApiMessageResponse.ok("File deleted."));
    }
}
