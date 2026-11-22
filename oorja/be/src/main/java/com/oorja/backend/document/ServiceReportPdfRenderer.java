package com.oorja.backend.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfPTable;
import com.oorja.backend.config.CompanyProperties;
import com.oorja.backend.entity.BookingAttachment;
import com.oorja.backend.entity.ServiceReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

import static com.oorja.backend.document.PdfCanvas.*;

/**
 * Renders the completion report a customer downloads after a visit.
 *
 * The ordering is chosen for the person reading it six months later, not
 * for the technician who filled it in: what was done, what we found, what
 * changed measurably, and what they should do next. Recommendations and the
 * next service date come last because that's what gets acted on.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceReportPdfRenderer {

    private final CompanyProperties company;
    private final ObjectMapper objectMapper;

    public byte[] render(ServiceReport report, List<BookingAttachment> photos) {
        try (PdfCanvas pdf = new PdfCanvas()) {
            pdf.header(
                    company.getName(),
                    company.addressBlock(),
                    "Service Report",
                    report.getReportNumber(),
                    "Visit on " + date(report.getServiceDate()));

            pdf.facts(
                    "Booking", report.getBooking().getToken(),
                    "Service", report.getBooking().getServiceLabel() != null
                            ? report.getBooking().getServiceLabel() : report.getBooking().getServiceType(),
                    "Customer", report.getCustomer().getName(),
                    "Site", report.getBooking().getLocation(),
                    "Technician", report.getTechnician() != null ? report.getTechnician().getName() : null,
                    "On site", window(report));

            pdf.rule().heading("Work performed");
            pdf.paragraph(report.getWorkPerformed());

            renderChecklist(pdf, report);

            if (hasReadings(report)) {
                pdf.heading("Measured output");
                PdfPTable table = pdf.itemsTable(new float[]{ 2f, 1f, 1f, 1f },
                        "Reading", "Before", "After", "Change");
                BigDecimal delta = report.improvementKw();
                pdf.row(table, "Plant output (kW)",
                        text(report.getOutputBeforeKw()),
                        text(report.getOutputAfterKw()),
                        delta == null ? "\u2014" : (delta.signum() > 0 ? "+" : "") + delta.toPlainString());
                pdf.add(table);

                if (delta != null && delta.signum() > 0 && report.getOutputBeforeKw() != null
                        && report.getOutputBeforeKw().signum() > 0) {
                    BigDecimal pct = delta.multiply(BigDecimal.valueOf(100))
                            .divide(report.getOutputBeforeKw(), 1, java.math.RoundingMode.HALF_UP);
                    pdf.paragraph("Output improved by " + pct.toPlainString() + "% after this visit.");
                }
            }

            if (report.getPanelsCleaned() != null) {
                pdf.paragraph("Panels cleaned: " + report.getPanelsCleaned());
            }

            if (notBlank(report.getObservations())) {
                pdf.heading("Observations").paragraph(report.getObservations());
            }
            if (notBlank(report.getPartsReplaced())) {
                pdf.heading("Parts replaced").paragraph(report.getPartsReplaced());
            }
            if (notBlank(report.getRecommendations())) {
                pdf.heading("Our recommendations").paragraph(report.getRecommendations());
            }
            if (report.getNextServiceDue() != null) {
                pdf.paragraph("Next service due: " + date(report.getNextServiceDue()) + ".");
            }

            if (photos != null && !photos.isEmpty()) {
                pdf.rule().heading("Attached photos and documents");
                PdfPTable table = pdf.itemsTable(new float[]{ 3f, 1.2f }, "File", "Added");
                for (BookingAttachment a : photos) {
                    pdf.row(table, a.getFileName(), dateTime(a.getCreatedAt()));
                }
                pdf.add(table);
                pdf.paragraph("These files are available to view and download in the app under this booking.");
            }

            if (notBlank(report.getCustomerRemarks()) || notBlank(report.getCustomerSignatureName())) {
                pdf.rule().heading("Customer acknowledgement");
                pdf.paragraph(report.getCustomerRemarks());
                if (notBlank(report.getCustomerSignatureName())) {
                    pdf.paragraph("Acknowledged on site by " + report.getCustomerSignatureName() + ".");
                }
            }

            pdf.footnote("Prepared by " + (report.getPreparedBy() == null ? company.getName() : report.getPreparedBy())
                    + (report.getPublishedAt() != null ? " · Published " + dateTime(report.getPublishedAt()) : "")
                    + ". Questions about this visit? Raise a support ticket in the app quoting "
                    + report.getReportNumber() + ".");

            return pdf.finish();
        }
    }

    /**
     * The checklist is stored as free-form JSON so ops can change it without
     * a migration. That flexibility ends here: malformed JSON must never
     * stop a customer downloading their report, so it's skipped and logged.
     */
    private void renderChecklist(PdfCanvas pdf, ServiceReport report) {
        if (!notBlank(report.getChecklistJson())) return;
        try {
            JsonNode items = objectMapper.readTree(report.getChecklistJson());
            if (!items.isArray() || items.isEmpty()) return;

            pdf.heading("Checks carried out");
            PdfPTable table = pdf.itemsTable(new float[]{ 3f, 0.8f, 2f }, "Check", "Result", "Note");
            for (JsonNode item : items) {
                boolean ok = item.path("ok").asBoolean(true);
                pdf.row(table,
                        item.path("label").asText(""),
                        ok ? "Pass" : "Attention",
                        item.path("note").asText(""));
            }
            pdf.add(table);
        } catch (Exception e) {
            log.warn("Skipping malformed checklist JSON on report {}: {}", report.getReportNumber(), e.getMessage());
        }
    }

    private static boolean hasReadings(ServiceReport r) {
        return r.getOutputBeforeKw() != null || r.getOutputAfterKw() != null;
    }

    private static String window(ServiceReport r) {
        if (r.getStartedAt() == null && r.getFinishedAt() == null) return null;
        if (r.getStartedAt() != null && r.getFinishedAt() != null) {
            return dateTime(r.getStartedAt()) + " \u2013 "
                    + r.getFinishedAt().toLocalTime().toString();
        }
        return dateTime(r.getStartedAt() != null ? r.getStartedAt() : r.getFinishedAt());
    }

    private static String text(BigDecimal value) {
        return value == null ? "\u2014" : value.toPlainString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
