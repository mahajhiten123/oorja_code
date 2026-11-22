package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.QuotationStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Prices a single installation-quote-request booking. One quotation per
 * booking (the same quotation is revised and re-sent rather than creating
 * a new one) - site photos/documents live on the booking itself via
 * {@link BookingAttachment}, so this entity doesn't duplicate them.
 */
@Entity
@Table(name = "quotations")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Quotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public-facing number, e.g. QUO-26-00042 */
    @Column(name = "quotation_number", nullable = false, unique = true)
    private String quotationNumber;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "subtotal", precision = 12, scale = 2)
    private BigDecimal subtotal;

    @Column(name = "discount_amount", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "tax_amount", precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** subtotal - discountAmount + taxAmount. What the customer actually pays. */
    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private QuotationStatus status = QuotationStatus.DRAFT;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    /** Customer-visible notes (scope, inclusions, warranty terms, etc.) */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "viewed_at")
    private LocalDateTime viewedAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    /** Set once the customer has paid for an accepted quote (Razorpay payment id). */
    @Column(name = "payment_ref")
    private String paymentRef;

    /**
     * Mirror of the current {@link QuotationVersion}'s number. The fields
     * above (subtotal/discountAmount/taxAmount/totalAmount/notes/validUntil/
     * status/sentAt/viewedAt/respondedAt/paymentRef) are a read-cache of that
     * version's values, kept in sync by QuotationService whenever the
     * current version changes. The real, immutable-once-sent history lives
     * in {@code quotation_versions} — see QuotationVersion.
     */
    @Column(name = "current_version_number", nullable = false)
    @Builder.Default
    private Integer currentVersionNumber = 1;

    /**
     * Which version the customer accepted, if any. Once set (and especially
     * once that version is paid), no new version may be created for this
     * quotation — see QuotationService.getOrCreateEditableDraft.
     */
    @Column(name = "accepted_version_id")
    private Long acceptedVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
