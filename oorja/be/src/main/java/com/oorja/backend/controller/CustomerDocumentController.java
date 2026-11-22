package com.oorja.backend.controller;

import com.oorja.backend.dto.response.InvoiceResponse;
import com.oorja.backend.dto.response.ServiceReportResponse;
import com.oorja.backend.entity.Invoice;
import com.oorja.backend.entity.ServiceReport;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.InvoiceService;
import com.oorja.backend.service.ServiceReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Everything a customer can download: tax invoices, credit notes, and the
 * service report for a completed visit.
 *
 * PDFs are streamed as an attachment with a real filename, so a browser or
 * mobile webview saves {@code INV-26-00231.pdf} rather than a random blob.
 * Ownership is checked in the service layer for every one of these, because
 * a download endpoint that trusts an id is how one customer reads another
 * customer's invoice.
 */
@RestController
@RequestMapping("/api/customer")
@RequiredArgsConstructor
public class CustomerDocumentController {

    private final InvoiceService invoiceService;
    private final ServiceReportService serviceReportService;

    // ---------- Invoices ----------

    @GetMapping("/invoices")
    public List<InvoiceResponse> myInvoices() {
        return invoiceService.findForCustomer(CurrentUser.get().userId());
    }

    @GetMapping("/invoices/{id}/pdf")
    public ResponseEntity<byte[]> invoicePdf(@PathVariable Long id,
                                             @RequestParam(defaultValue = "attachment") String disposition) {
        Invoice invoice = invoiceService.getForCustomer(id, CurrentUser.get().userId());
        return pdf(invoiceService.pdf(invoice), invoice.fileName(), disposition);
    }

    // ---------- Service reports ----------

    @GetMapping("/service-reports")
    public List<ServiceReportResponse> myReports() {
        return serviceReportService.findForCustomer(CurrentUser.get().userId());
    }

    @GetMapping("/bookings/{bookingId}/service-report")
    public ServiceReportResponse report(@PathVariable Long bookingId) {
        return serviceReportService.getPublishedResponse(bookingId, CurrentUser.get().userId());
    }

    @GetMapping("/bookings/{bookingId}/service-report/pdf")
    public ResponseEntity<byte[]> reportPdf(@PathVariable Long bookingId,
                                            @RequestParam(defaultValue = "attachment") String disposition) {
        ServiceReport report = serviceReportService.getPublishedForCustomer(bookingId, CurrentUser.get().userId());
        return pdf(serviceReportService.pdf(report), report.fileName(), disposition);
    }

    /**
     * "inline" lets the app preview a document in a viewer; "attachment"
     * forces a save. Anything else is treated as attachment rather than
     * echoed into the header — a Content-Disposition built from unvalidated
     * input is a header-injection hole.
     */
    private ResponseEntity<byte[]> pdf(byte[] bytes, String fileName, String disposition) {
        String mode = "inline".equalsIgnoreCase(disposition) ? "inline" : "attachment";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, mode + "; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, no-store")
                .body(bytes);
    }
}
