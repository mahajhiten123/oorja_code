package com.oorja.backend.controller;

import com.oorja.backend.dto.request.AssignTechnicianRequest;
import com.oorja.backend.dto.request.CancelBookingRequest;
import com.oorja.backend.dto.request.CompleteBookingRequest;
import com.oorja.backend.dto.request.DeleteAttachmentRequest;
import com.oorja.backend.dto.request.UpdateBookingRequest;
import com.oorja.backend.dto.response.ApiMessageResponse;
import com.oorja.backend.dto.response.BookingResponse;
import com.oorja.backend.entity.BookingAttachment;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.BookingService;
import com.oorja.backend.service.AttachmentService;
import com.oorja.backend.storage.StorageProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final StorageProvider storageProvider;
    private final AttachmentService attachmentService;

    @GetMapping
    public Page<BookingResponse> getAll(@PageableDefault(size = 500) Pageable pageable) {
        return bookingService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public BookingResponse getOne(@PathVariable Long id) {
        return bookingService.findById(id);
    }

    /** Assigns a technician to a booking (moves PENDING -> ASSIGNED). */
    @PostMapping("/{id}/assign-technician")
    public BookingResponse assignTechnician(@PathVariable Long id, @Valid @RequestBody AssignTechnicianRequest request) {
        return bookingService.assignTechnician(id, request.technicianId());
    }

    /** Marks a booking complete — flips it to "Completed" immediately for the customer. */
    @PostMapping("/{id}/complete")
    public BookingResponse complete(@PathVariable Long id, @Valid @RequestBody(required = false) CompleteBookingRequest request) {
        return bookingService.markCompleted(id, request != null ? request.notes() : null);
    }

    /** Admin-initiated cancellation. */
    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable Long id, @Valid @RequestBody(required = false) CancelBookingRequest request) {
        return bookingService.cancelByAdmin(id, request != null ? request.reason() : null);
    }

    /** Streams an uploaded site photo/document back to the admin console. */
    @GetMapping("/attachments/{attachmentId}")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable Long attachmentId) {
        BookingAttachment attachment = attachmentService.getForAdmin(attachmentId);
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
     * Soft-deletes an attachment. Admins may remove any file; the row is kept
     * as the audit record (who deleted it, when, and why) and the underlying
     * object is destroyed by the nightly purge job after the retention window.
     */
    @DeleteMapping("/attachments/{attachmentId}")
    public ResponseEntity<ApiMessageResponse> deleteAttachment(@PathVariable Long attachmentId,
                                                                @Valid @RequestBody(required = false) DeleteAttachmentRequest request) {
        attachmentService.deleteAsAdmin(attachmentId, CurrentUser.get().userId(),
                request != null ? request.reason() : null);
        return ResponseEntity.ok(ApiMessageResponse.ok("File deleted."));
    }

    @PatchMapping("/{id}")
    public BookingResponse update(@PathVariable Long id, @Valid @RequestBody UpdateBookingRequest request) {
        return bookingService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ApiMessageResponse delete(@PathVariable Long id) {
        bookingService.delete(id);
        return ApiMessageResponse.ok("Booking deleted");
    }
}
