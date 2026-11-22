package com.oorja.backend.controller;

import com.oorja.backend.dto.request.ServiceReportRequest;
import com.oorja.backend.dto.response.InvoiceResponse;
import com.oorja.backend.dto.response.ServiceReportResponse;
import com.oorja.backend.entity.Invoice;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.InvoiceService;
import com.oorja.backend.service.ServiceReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin console → billing documents and service reports. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminDocumentController {

    private final InvoiceService invoiceService;
    private final ServiceReportService serviceReportService;

    @GetMapping("/invoices")
    public Page<InvoiceResponse> invoices(@PageableDefault(size = 20) Pageable pageable) {
        return invoiceService.findAllForAdmin(pageable);
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long id) {
        Invoice invoice = invoiceService.getForAdmin(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + invoice.fileName() + "\"")
                .body(invoiceService.pdf(invoice));
    }

    @GetMapping("/service-reports")
    public List<ServiceReportResponse> reports() {
        return serviceReportService.findAllForAdmin();
    }

    @GetMapping("/bookings/{bookingId}/service-report")
    public ServiceReportResponse report(@PathVariable Long bookingId) {
        return serviceReportService.findForAdmin(bookingId);
    }

    /** Save the draft. Safe to call repeatedly as the technician types. */
    @PutMapping("/bookings/{bookingId}/service-report")
    public ServiceReportResponse saveDraft(@PathVariable Long bookingId,
                                           @Valid @RequestBody ServiceReportRequest request) {
        return serviceReportService.save(bookingId, request, actor());
    }

    /**
     * Publish to the customer, and by default close the booking out at the
     * same time — a completed visit and the record of it belong together.
     */
    @PostMapping("/bookings/{bookingId}/service-report/publish")
    public ServiceReportResponse publish(@PathVariable Long bookingId,
                                         @RequestParam(defaultValue = "true") boolean completeBooking) {
        return serviceReportService.publish(bookingId, completeBooking);
    }

    private String actor() {
        var user = CurrentUser.get();
        return user != null && user.email() != null ? user.email() : "admin";
    }
}
