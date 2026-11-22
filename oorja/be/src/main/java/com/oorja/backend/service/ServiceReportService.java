package com.oorja.backend.service;

import com.oorja.backend.document.ServiceReportPdfRenderer;
import com.oorja.backend.dto.request.ServiceReportRequest;
import com.oorja.backend.dto.response.ServiceReportResponse;
import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.BookingAttachment;
import com.oorja.backend.entity.ServiceReport;
import com.oorja.backend.entity.enums.BookingStatus;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingAttachmentRepository;
import com.oorja.backend.repository.BookingRepository;
import com.oorja.backend.repository.ServiceReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The completion report for a finished visit.
 *
 * Draft-then-publish is the whole design. A technician saving notes from a
 * phone at a site is drafting; the customer sees nothing until an admin
 * publishes. After publishing, edits are still allowed — sites throw up
 * corrections — but each one re-stamps {@code updatedAt}, and the PDF is
 * rendered from the row at download time so the customer always has the
 * current version rather than a stale cached file.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ServiceReportService {

    private final ServiceReportRepository reportRepository;
    private final BookingRepository bookingRepository;
    private final BookingAttachmentRepository attachmentRepository;
    private final ServiceReportPdfRenderer renderer;
    private final CodeGeneratorService codeGenerator;
    private final EmailService emailService;

    // ---------- Admin / technician ----------

    /** Creates or updates the draft for a booking. Idempotent by booking. */
    @Transactional
    public ServiceReportResponse save(Long bookingId, ServiceReportRequest request, String preparedBy) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> ApiException.notFound("Booking not found: " + bookingId));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw ApiException.badRequest("A cancelled booking has no service to report on.");
        }

        ServiceReport report = reportRepository.findByBookingId(bookingId).orElseGet(() ->
                ServiceReport.builder()
                        .reportNumber(codeGenerator.nextServiceReportNumber())
                        .booking(booking)
                        .customer(booking.getCustomer())
                        .serviceDate(booking.getScheduledDate())
                        .workPerformed("")
                        .build());

        report.setTechnician(booking.getTechnician());
        report.setServiceDate(request.serviceDate() != null ? request.serviceDate() : booking.getScheduledDate());
        report.setStartedAt(request.startedAt());
        report.setFinishedAt(request.finishedAt());
        report.setWorkPerformed(request.workPerformed());
        report.setObservations(request.observations());
        report.setPartsReplaced(request.partsReplaced());
        report.setRecommendations(request.recommendations());
        report.setChecklistJson(request.checklistJson());
        report.setPanelsCleaned(request.panelsCleaned());
        report.setOutputBeforeKw(request.outputBeforeKw());
        report.setOutputAfterKw(request.outputAfterKw());
        report.setNextServiceDue(request.nextServiceDue());
        report.setCustomerSignatureName(request.customerSignatureName());
        report.setCustomerRemarks(request.customerRemarks());
        report.setPreparedBy(preparedBy);

        return ServiceReportResponse.from(reportRepository.save(report), attachmentsFor(bookingId));
    }

    /**
     * Publishes the report to the customer and closes the booking out.
     *
     * Marking the visit complete and handing over the record of it are the
     * same moment from the customer's side, so they're the same call here —
     * a completed booking with no report is the gap that generates "what did
     * they actually do?" support tickets.
     */
    @Transactional
    public ServiceReportResponse publish(Long bookingId, boolean alsoCompleteBooking) {
        ServiceReport report = reportRepository.findByBookingId(bookingId)
                .orElseThrow(() -> ApiException.notFound("No service report has been drafted for this booking yet."));

        if (report.getWorkPerformed() == null || report.getWorkPerformed().isBlank()) {
            throw ApiException.badRequest("Fill in what work was performed before publishing this report.");
        }

        boolean firstPublish = !Boolean.TRUE.equals(report.getPublished());
        if (firstPublish) {
            report.setPublished(true);
            report.setPublishedAt(LocalDateTime.now());
        }

        if (alsoCompleteBooking) {
            Booking booking = report.getBooking();
            if (booking.getStatus() != BookingStatus.CANCELLED) {
                booking.setStatus(BookingStatus.COMPLETED);
                bookingRepository.save(booking);
            }
        }

        report = reportRepository.save(report);

        // Only on the transition into "published" — an edit made afterwards
        // (a site correction) must not re-send the report as a new email.
        if (firstPublish) {
            try {
                emailService.sendServiceReportEmail(
                        report.getCustomer().getEmail(),
                        report.getCustomer().getName(),
                        report.getReportNumber(),
                        report.getBooking().getToken(),
                        renderer.render(report, attachmentsFor(bookingId)),
                        report.getReportNumber() + ".pdf");
            } catch (Exception e) {
                log.error("Couldn't send service report email for {}: {}", report.getReportNumber(), e.getMessage());
            }
        }

        return ServiceReportResponse.from(report, attachmentsFor(bookingId));
    }

    @Transactional(readOnly = true)
    public ServiceReportResponse findForAdmin(Long bookingId) {
        ServiceReport report = reportRepository.findByBookingId(bookingId)
                .orElseThrow(() -> ApiException.notFound("No service report for this booking yet."));
        return ServiceReportResponse.from(report, attachmentsFor(bookingId));
    }

    @Transactional(readOnly = true)
    public List<ServiceReportResponse> findAllForAdmin() {
        return reportRepository.findAllByOrderByServiceDateDesc().stream()
                .map(r -> ServiceReportResponse.from(r, attachmentsFor(r.getBooking().getId())))
                .toList();
    }

    // ---------- Customer ----------

    @Transactional(readOnly = true)
    public List<ServiceReportResponse> findForCustomer(Long customerId) {
        return reportRepository.findByCustomerIdAndPublishedTrueOrderByServiceDateDesc(customerId).stream()
                .map(r -> ServiceReportResponse.from(r, attachmentsFor(r.getBooking().getId())))
                .toList();
    }

    /** A published report the given customer owns, or a 404 — drafts don't exist as far as customers are concerned. */
    @Transactional(readOnly = true)
    public ServiceReport getPublishedForCustomer(Long bookingId, Long customerId) {
        ServiceReport report = reportRepository.findByBookingId(bookingId)
                .orElseThrow(() -> ApiException.notFound("No service report is available for this booking yet."));
        if (!report.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("You can't view someone else's service report.");
        }
        if (!Boolean.TRUE.equals(report.getPublished())) {
            throw ApiException.notFound("This report is still being prepared. We'll notify you when it's ready.");
        }
        return report;
    }

    @Transactional(readOnly = true)
    public ServiceReportResponse getPublishedResponse(Long bookingId, Long customerId) {
        ServiceReport report = getPublishedForCustomer(bookingId, customerId);
        return ServiceReportResponse.from(report, attachmentsFor(bookingId));
    }

    @Transactional(readOnly = true)
    public byte[] pdf(ServiceReport report) {
        return renderer.render(report, attachmentsFor(report.getBooking().getId()));
    }

    /** Only live attachments: a deleted photo must not reappear on a re-downloaded report. */
    private List<BookingAttachment> attachmentsFor(Long bookingId) {
        return attachmentRepository.findByBookingIdAndDeletedAtIsNull(bookingId);
    }
}
