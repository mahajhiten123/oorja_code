package com.oorja.backend.document;

import com.oorja.backend.config.CompanyProperties;
import com.oorja.backend.entity.Invoice;
import com.lowagie.text.pdf.PdfPTable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.oorja.backend.document.PdfCanvas.*;

/**
 * Renders a tax invoice or credit note.
 *
 * Reads only from the {@link Invoice} row — never from the live customer,
 * booking or price list. That's the point of freezing the figures at issue
 * time: a document downloaded today and re-downloaded in two years is
 * byte-for-byte the same statement of what was charged.
 */
@Component
@RequiredArgsConstructor
public class InvoicePdfRenderer {

    private final CompanyProperties company;

    public byte[] render(Invoice inv) {
        boolean creditNote = inv.isCreditNote();
        // A credit note is money going back, so every figure prints negative.
        int sign = creditNote ? -1 : 1;

        try (PdfCanvas pdf = new PdfCanvas()) {
            pdf.header(
                    company.getName(),
                    company.addressBlock(),
                    creditNote ? "Credit Note" : "Tax Invoice",
                    inv.getInvoiceNumber(),
                    "Issued " + dateTime(inv.getIssuedAt()));

            pdf.twoColumns(
                    "Billed to",
                    billedToBlock(inv),
                    creditNote ? "Refund details" : "Payment details",
                    paymentBlock(inv));

            if (creditNote && inv.getOriginalInvoice() != null) {
                pdf.paragraph("Issued against invoice " + inv.getOriginalInvoice().getInvoiceNumber()
                        + " dated " + dateTime(inv.getOriginalInvoice().getIssuedAt()) + ".");
            }

            pdf.rule();

            PdfPTable items = pdf.itemsTable(new float[]{ 4f, 1.3f }, "Description", "Amount");
            pdf.row(items, inv.getDescription(), rupees(sign * inv.getSubtotalPaise()));
            pdf.add(items);

            long taxable = inv.getSubtotalPaise() - inv.getDiscountPaise();
            pdf.totals(new String[][]{
                    { "Subtotal", rupees(sign * inv.getSubtotalPaise()) },
                    { "Discount", inv.getDiscountPaise() > 0 ? rupees(sign * -inv.getDiscountPaise()) : null },
                    { "Taxable value", rupees(sign * taxable) },
                    { "GST " + percent(inv.getTaxRateBps()), rupees(sign * inv.getTaxPaise()) }
            }, creditNote ? "Total refunded" : "Total paid", rupees(sign * inv.getTotalPaise()));

            if (inv.getBooking() != null) {
                pdf.rule().heading("Service reference");
                pdf.facts(
                        "Booking", inv.getBooking().getToken(),
                        "Service", inv.getBooking().getServiceLabel() != null
                                ? inv.getBooking().getServiceLabel() : inv.getBooking().getServiceType(),
                        "Scheduled", date(inv.getBooking().getScheduledDate()),
                        "Site", inv.getBooking().getLocation());
            }

            if (inv.getNotes() != null && !inv.getNotes().isBlank()) {
                pdf.heading("Notes").paragraph(inv.getNotes());
            }

            pdf.footnote(company.getInvoiceFooter());
            return pdf.finish();
        }
    }

    private String billedToBlock(Invoice inv) {
        StringBuilder sb = new StringBuilder(inv.getBillToName());
        if (inv.getBillToAddress() != null && !inv.getBillToAddress().isBlank()) {
            sb.append('\n').append(inv.getBillToAddress());
        }
        if (inv.getBillToPhone() != null) sb.append('\n').append(inv.getBillToPhone());
        if (inv.getBillToEmail() != null) sb.append('\n').append(inv.getBillToEmail());
        if (inv.getPlaceOfSupply() != null) sb.append("\nPlace of supply: ").append(inv.getPlaceOfSupply());
        return sb.toString();
    }

    private String paymentBlock(Invoice inv) {
        StringBuilder sb = new StringBuilder();
        sb.append("Status: ").append(inv.getStatus().label());
        if (inv.getPaymentMethod() != null) sb.append('\n').append(inv.getPaymentMethod());
        if (inv.getPaymentReference() != null) sb.append("\nRef: ").append(inv.getPaymentReference());
        if (inv.getPaidAt() != null) sb.append('\n').append(dateTime(inv.getPaidAt()));
        return sb.toString();
    }
}
