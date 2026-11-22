package com.oorja.backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Year;

/**
 * Human-readable reference numbers (TKN-26-00231, PAY-26-00231, ...).
 * <p>
 * These used to be {@code max(id) + 1} read before the insert. That is a race:
 * two concurrent requests read the same max and both produce the same number,
 * so one of them dies on the unique constraint - for a perfectly valid booking
 * or payment. Numbers now come from Postgres sequences (V20), which hand out a
 * distinct value to every caller atomically.
 * <p>
 * Sequences are not rolled back, so a failed transaction leaves a gap. That is
 * fine for bookings, payments, tickets etc. Tax documents are the exception:
 * invoice / credit-note numbers must be consecutive, so they use a counter row
 * ({@code document_counters}) whose increment commits or rolls back together
 * with the invoice insert, at the cost of serialising invoice issuance.
 */
@Service
@RequiredArgsConstructor
public class CodeGeneratorService {

    private final JdbcTemplate jdbc;

    public String nextCustomerCode() {
        return "CUS" + seq("seq_customer_code");
    }

    public String nextPlantCode() {
        return String.format("PLT%02d", seq("seq_plant_code"));
    }

    public String nextTechnicianCode() {
        return String.format("TCH%03d", seq("seq_technician_code"));
    }

    public String nextBookingToken() {
        return String.format("TKN-%s-%05d", yy(), seq("seq_booking_token"));
    }

    public String nextQuotationNumber() {
        return String.format("QUO-%s-%05d", yy(), seq("seq_quotation_number"));
    }

    /** Public refund reference, e.g. RFD-26-00231 — printed on statements and quoted to support. */
    public String nextRefundNumber() {
        return String.format("RFD-%s-%05d", yy(), seq("seq_refund_number"));
    }

    /**
     * INV-26-00231. Invoice and credit-note numbers share one counter
     * deliberately: a tax document reference has to be unique (and
     * consecutive) across the whole book, not just within its own type.
     * MUST be called inside the transaction that inserts the invoice.
     */
    public String nextInvoiceNumber() {
        return String.format("INV-%s-%05d", yy(), nextInvoiceCounter());
    }

    public String nextCreditNoteNumber() {
        return String.format("CRN-%s-%05d", yy(), nextInvoiceCounter());
    }

    public String nextServiceReportNumber() {
        return String.format("SRV-%s-%05d", yy(), seq("seq_service_report_number"));
    }

    public String nextTicketNumber() {
        return String.format("TKT-%s-%05d", yy(), seq("seq_ticket_number"));
    }

    public String nextPaymentNumber() {
        return String.format("PAY-%s-%05d", yy(), seq("seq_payment_number"));
    }

    // ---------------------------------------------------------------

    private long seq(String sequenceName) {
        // sequenceName is always one of the literals above, never user input.
        Long v = jdbc.queryForObject("select nextval('" + sequenceName + "')", Long.class);
        return v;
    }

    /** Row-locking increment: concurrent issuers queue here, and a rollback returns the number. */
    private long nextInvoiceCounter() {
        Long v = jdbc.queryForObject(
                "update document_counters set next_value = next_value + 1 "
                        + "where name = 'invoice' returning next_value - 1", Long.class);
        return v;
    }

    private String yy() {
        return String.valueOf(Year.now().getValue()).substring(2);
    }
}
