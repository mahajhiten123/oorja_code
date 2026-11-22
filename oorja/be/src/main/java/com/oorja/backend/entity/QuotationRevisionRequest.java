package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.RevisionReason;
import com.oorja.backend.entity.enums.RevisionRequestStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A customer's "please change this" request against a specific sent
 * {@link QuotationVersion}. Doesn't itself change anything — it's the
 * customer-facing counterpart to the admin creating a new version, and the
 * paper trail for why that new version exists.
 */
@Entity
@Table(name = "quotation_revision_requests")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuotationRevisionRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_id", nullable = false)
    private Quotation quotation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    /** The version the customer was looking at when they asked for a change. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quotation_version_id", nullable = false)
    private QuotationVersion quotationVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RevisionReason reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private RevisionRequestStatus status = RevisionRequestStatus.OPEN;

    /** Admin's note on how this was resolved or why it was rejected. */
    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** Admin id who resolved/rejected this request. */
    @Column(name = "resolved_by")
    private Long resolvedBy;
}
