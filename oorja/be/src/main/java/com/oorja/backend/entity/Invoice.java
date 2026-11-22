package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.InvoiceStatus;
import com.oorja.backend.entity.enums.InvoiceType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * An issued tax invoice or credit note.
 *
 * Everything on this row is a frozen snapshot: the customer's name and
 * address as they read on the day, the tax rate in force then, and the
 * amounts as charged. Nothing here is derived at read time, because a
 * document a customer has already downloaded and filed must not quietly
 * change when a price list or a profile does.
 */
@Entity
@Table(name = "invoices")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** INV-26-00231, or CRN-26-00014 for a credit note. */
    @Column(name = "invoice_number", nullable = false, unique = true, updatable = false)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InvoiceType type = InvoiceType.TAX_INVOICE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.PAID;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(name = "quotation_id")
    private Long quotationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_id")
    private Refund refund;

    /** For a credit note: the invoice being reversed. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_invoice_id")
    private Invoice originalInvoice;

    // ---------- Billed to (snapshot) ----------

    @Column(name = "bill_to_name", nullable = false)
    private String billToName;

    @Column(name = "bill_to_email")
    private String billToEmail;

    @Column(name = "bill_to_phone")
    private String billToPhone;

    @Column(name = "bill_to_address", columnDefinition = "TEXT")
    private String billToAddress;

    @Column(name = "place_of_supply")
    private String placeOfSupply;

    // ---------- Amounts ----------

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "subtotal_paise", nullable = false)
    private Long subtotalPaise;

    @Column(name = "discount_paise", nullable = false)
    @Builder.Default
    private Long discountPaise = 0L;

    /** Tax rate in force when this was issued, in basis points (1800 = 18%). */
    @Column(name = "tax_rate_bps", nullable = false)
    @Builder.Default
    private Integer taxRateBps = 1800;

    @Column(name = "tax_paise", nullable = false)
    @Builder.Default
    private Long taxPaise = 0L;

    @Column(name = "total_paise", nullable = false)
    private Long totalPaise;

    @Column(nullable = false)
    @Builder.Default
    private String currency = "INR";

    // ---------- Settlement ----------

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "payment_reference")
    private String paymentReference;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "issued_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime issuedAt = LocalDateTime.now();

    /** Cached PDF object key; regenerated on demand if the object has gone. */
    @Column(name = "pdf_storage_key")
    private String pdfStorageKey;

    @Column(name = "pdf_generated_at")
    private LocalDateTime pdfGeneratedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Transient
    public boolean isCreditNote() {
        return type == InvoiceType.CREDIT_NOTE;
    }

    /** Filename a customer downloads it as. */
    @Transient
    public String fileName() {
        return invoiceNumber + ".pdf";
    }
}
