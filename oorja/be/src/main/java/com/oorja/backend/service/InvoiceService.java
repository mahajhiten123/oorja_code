package com.oorja.backend.service;

import com.oorja.backend.config.CompanyProperties;
import com.oorja.backend.document.InvoicePdfRenderer;
import com.oorja.backend.dto.response.InvoiceResponse;
import com.oorja.backend.entity.*;
import com.oorja.backend.entity.enums.InvoiceStatus;
import com.oorja.backend.entity.enums.InvoiceType;
import com.oorja.backend.entity.enums.PaymentStatus;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.InvoiceRepository;
import com.oorja.backend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Issues and serves tax invoices and credit notes.
 *
 * Two deliberate choices:
 *
 * <p><b>Issuance is lazy plus swept, not hooked.</b> Rather than threading
 * an invoice call through every payment path (client verify, webhook
 * fallback, quotation settlement), an invoice is created the first time
 * anyone asks for one, and a sweep issues any that were missed. There is
 * exactly one code path that creates an invoice, so there's exactly one
 * place a duplicate or a wrong figure could come from — and the unique index
 * on {@code payment_id} makes a duplicate impossible regardless.
 *
 * <p><b>PDFs are rendered on demand, not stored.</b> The source of truth is
 * the frozen invoice row; rendering is deterministic and takes milliseconds,
 * so caching a binary that must never diverge from it buys nothing and adds
 * a second thing to keep in sync. The {@code pdf_storage_key} column exists
 * for the day volume makes that trade worth revisiting.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoicePdfRenderer renderer;
    private final CodeGeneratorService codeGenerator;
    private final CompanyProperties company;
    private final EmailService emailService;

    // ---------- Issuance ----------

    /**
     * The tax invoice for a captured payment, creating it if this is the
     * first time anyone has asked.
     */
    @Transactional
    public Invoice issueForPayment(Payment payment) {
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw ApiException.badRequest("An invoice is only issued once a payment has been captured.");
        }

        Invoice existing = invoiceRepository.findByPaymentIdAndType(payment.getId(), InvoiceType.TAX_INVOICE).orElse(null);
        if (existing != null) {
            return existing;
        }

        Customer customer = payment.getCustomer();
        Booking booking = payment.getBooking();
        long total = payment.getAmountPaise();

        // The payment ledger stores one figure: what was actually charged.
        // GST in India is quoted tax-inclusive at checkout, so the taxable
        // value is back-computed from the total at the rate in force on the
        // day, and the rate is then frozen on the row. Deriving it later
        // from a config that has since changed would silently restate old
        // invoices.
        int rateBps = company.getTaxRateBps();
        long taxable = Math.round(total / (1 + rateBps / 10_000.0));
        long tax = total - taxable;

        Invoice invoice = Invoice.builder()
                .invoiceNumber(codeGenerator.nextInvoiceNumber())
                .type(InvoiceType.TAX_INVOICE)
                .status(InvoiceStatus.PAID)
                .customer(customer)
                .booking(booking)
                .payment(payment)
                .quotationId(payment.getQuotationId())
                .billToName(customer.getName())
                .billToEmail(customer.getEmail())
                .billToPhone(customer.getPhone())
                .billToAddress(booking != null && booking.getLocation() != null
                        ? booking.getLocation() : customer.getAddress())
                .placeOfSupply(company.getState())
                .description(describe(payment, booking))
                .subtotalPaise(taxable)
                .discountPaise(0L)
                .taxRateBps(rateBps)
                .taxPaise(tax)
                .totalPaise(total)
                .currency(payment.getCurrency())
                .paymentMethod("Razorpay")
                .paymentReference(payment.getRazorpayPaymentId() != null
                        ? payment.getRazorpayPaymentId() : payment.getRazorpayOrderId())
                .paidAt(payment.getCapturedAt())
                .build();

        invoice = invoiceRepository.save(invoice);

        // First issuance only — the unique index on payment_id guarantees
        // this branch runs at most once per payment, however it was reached
        // (client verify, webhook, or the sweep), so the customer is never
        // emailed the same receipt twice.
        try {
            emailService.sendPaymentReceiptEmail(
                    invoice.getBillToEmail(),
                    invoice.getBillToName(),
                    invoice.getInvoiceNumber(),
                    invoice.getTotalPaise(),
                    invoice.getCurrency(),
                    invoice.getPaidAt(),
                    renderer.render(invoice),
                    invoice.getInvoiceNumber() + ".pdf");
        } catch (Exception e) {
            log.error("Couldn't send payment receipt email for invoice {}: {}",
                    invoice.getInvoiceNumber(), e.getMessage());
        }

        return invoice;
    }

    /**
     * A completed refund produces a credit note against the original
     * invoice — never an edit to it. Called from the refund pipeline once
     * the provider has confirmed settlement.
     */
    @Transactional
    public Invoice issueCreditNote(Refund refund) {
        Invoice existing = invoiceRepository.findByRefundId(refund.getId()).orElse(null);
        if (existing != null) return existing;
        if (refund.getPayment() == null) {
            log.warn("Refund {} has no payment row; skipping credit note.", refund.getRefundNumber());
            return null;
        }

        Invoice original = invoiceRepository
                .findByPaymentIdAndType(refund.getPayment().getId(), InvoiceType.TAX_INVOICE)
                .orElseGet(() -> issueForPayment(refund.getPayment()));

        long total = refund.getAmountPaise();
        int rateBps = original.getTaxRateBps();
        long taxable = Math.round(total / (1 + rateBps / 10_000.0));
        long tax = total - taxable;

        Invoice creditNote = invoiceRepository.save(Invoice.builder()
                .invoiceNumber(codeGenerator.nextCreditNoteNumber())
                .type(InvoiceType.CREDIT_NOTE)
                .status(InvoiceStatus.ISSUED)
                .customer(refund.getCustomer())
                .booking(refund.getBooking())
                .payment(refund.getPayment())
                .refund(refund)
                .originalInvoice(original)
                .billToName(original.getBillToName())
                .billToEmail(original.getBillToEmail())
                .billToPhone(original.getBillToPhone())
                .billToAddress(original.getBillToAddress())
                .placeOfSupply(original.getPlaceOfSupply())
                .description("Refund against " + original.getInvoiceNumber()
                        + " \u2014 " + original.getDescription())
                .subtotalPaise(taxable)
                .taxRateBps(rateBps)
                .taxPaise(tax)
                .totalPaise(total)
                .currency(original.getCurrency())
                .paymentMethod("Razorpay refund")
                .paymentReference(refund.getRazorpayRefundId())
                .paidAt(refund.getCompletedAt())
                .notes(refund.getEligibilityNote())
                .build());

        // The original invoice's status reflects reality without its figures
        // being touched: the numbers on a filed document stay put.
        original.setStatus(total >= original.getTotalPaise()
                ? InvoiceStatus.REFUNDED : InvoiceStatus.PARTIALLY_REFUNDED);
        invoiceRepository.save(original);

        return creditNote;
    }

    /** Catches any captured payment whose invoice was never requested. */
    @Scheduled(cron = "${app.invoices.sweep-cron:0 15 * * * *}")
    @Transactional
    public void sweepUninvoicedPayments() {
        int issued = 0;
        for (Payment payment : paymentRepository.findAll()) {
            if (payment.getStatus() != PaymentStatus.CAPTURED) continue;
            if (invoiceRepository.findByPaymentIdAndType(payment.getId(), InvoiceType.TAX_INVOICE).isPresent()) continue;
            try {
                issueForPayment(payment);
                issued++;
            } catch (Exception e) {
                log.error("Could not issue an invoice for payment {}: {}", payment.getPaymentNumber(), e.getMessage());
            }
        }
        if (issued > 0) log.info("Invoice sweep issued {} invoice(s).", issued);
    }

    // ---------- Reads ----------

    /**
     * Everything this customer can be billed for, issuing anything missing
     * on the way so the list is never mysteriously short.
     */
    @Transactional
    public List<InvoiceResponse> findForCustomer(Long customerId) {
        for (Payment payment : paymentRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)) {
            if (payment.getStatus() != PaymentStatus.CAPTURED) continue;
            if (invoiceRepository.findByPaymentIdAndType(payment.getId(), InvoiceType.TAX_INVOICE).isEmpty()) {
                issueForPayment(payment);
            }
        }
        return invoiceRepository.findByCustomerIdOrderByIssuedAtDesc(customerId).stream()
                .map(InvoiceResponse::from).toList();
    }

    /** Paginated for the admin console list — an earlier unpaginated version loaded the whole table into memory. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InvoiceResponse> findAllForAdmin(org.springframework.data.domain.Pageable pageable) {
        return invoiceRepository.findAllByOrderByIssuedAtDesc(pageable).map(InvoiceResponse::from);
    }

    @Transactional(readOnly = true)
    public Invoice getForCustomer(Long invoiceId, Long customerId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ApiException.notFound("Invoice not found."));
        if (!invoice.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("You can't download someone else's invoice.");
        }
        return invoice;
    }

    @Transactional(readOnly = true)
    public Invoice getForAdmin(Long invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> ApiException.notFound("Invoice not found."));
    }

    /** Renders the PDF. Deterministic: the same row always produces the same document. */
    @Transactional(readOnly = true)
    public byte[] pdf(Invoice invoice) {
        return renderer.render(invoice);
    }

    private String describe(Payment payment, Booking booking) {
        if (booking != null) {
            String label = booking.getServiceLabel() != null ? booking.getServiceLabel() : booking.getServiceType();
            return label + " \u2014 booking " + booking.getToken();
        }
        if (payment.getQuotationId() != null) {
            return "Payment against quotation #" + payment.getQuotationId();
        }
        return "Solar services \u2014 payment " + payment.getPaymentNumber();
    }
}
