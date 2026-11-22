package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.QuotationVersionStatus;
import com.oorja.backend.entity.enums.SubjectType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One priced snapshot of a {@link Quotation} — v1, v2, v3…
 *
 * <b>Immutability rule:</b> {@link #subtotal}, {@link #discountAmount},
 * {@link #taxAmount}, {@link #totalAmount}, {@link #notes} and
 * {@link #validUntil} may only be written while {@link #status} is
 * {@code DRAFT}. The moment a version is sent, those fields are frozen for
 * the lifetime of the row — a price change is always a brand-new version,
 * never an edit of this one. See {@code QuotationService.getOrCreateEditableDraft}
 * for where that rule is enforced.
 *
 * Once {@link #paymentRef} is set, the version (and by extension the whole
 * quotation) is locked permanently: no further version may be created at
 * all, because the financial history behind a Razorpay payment must never
 * change after the fact.
 */
@Entity
@Table(name = "quotation_versions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    /** 1-based, monotonically increasing per quotation. */
    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

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

    /** Customer-visible notes for this specific version (scope, inclusions, warranty terms, etc.) */
    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private QuotationVersionStatus status = QuotationVersionStatus.DRAFT;

    /** Who authored this version. Null for the system-created empty v1 draft. */
    @Column(name = "created_by")
    private Long createdBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "created_by_type", length = 16)
    private SubjectType createdByType;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "viewed_at")
    private LocalDateTime viewedAt;

    @Column(name = "responded_at")
    private LocalDateTime respondedAt;

    /** Set exactly once, the moment this version is paid for. Locks it permanently. */
    @Column(name = "payment_ref")
    private String paymentRef;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public boolean isDraft() {
        return status == QuotationVersionStatus.DRAFT;
    }

    public boolean isPaid() {
        return paymentRef != null;
    }
}
