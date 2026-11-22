package com.oorja.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The completion record for a finished visit — what was done, what was
 * found, what the customer should do next.
 *
 * It's a draft until {@code published} is true. That matters: a technician
 * filling this in on a phone at a site shouldn't be publishing half a
 * sentence to the customer's app, and a report the customer has downloaded
 * shouldn't keep shifting underneath them afterwards.
 *
 * Photos are deliberately not duplicated here. The booking's existing
 * attachments already carry storage, retention and soft-delete; the report
 * PDF simply lists the ones marked as official documents.
 */
@Entity
@Table(name = "service_reports")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SRV-26-00231 */
    @Column(name = "report_number", nullable = false, unique = true, updatable = false)
    private String reportNumber;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technician_id")
    private Technician technician;

    @Column(name = "service_date", nullable = false)
    private LocalDate serviceDate;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "work_performed", nullable = false, columnDefinition = "TEXT")
    private String workPerformed;

    @Column(columnDefinition = "TEXT")
    private String observations;

    @Column(name = "parts_replaced", columnDefinition = "TEXT")
    private String partsReplaced;

    @Column(columnDefinition = "TEXT")
    private String recommendations;

    /** JSON array of {"label":…,"ok":true,"note":…}. Kept as text so ops can change the checklist without a migration. */
    @Column(name = "checklist_json", columnDefinition = "TEXT")
    private String checklistJson;

    @Column(name = "panels_cleaned")
    private Integer panelsCleaned;

    @Column(name = "output_before_kw", precision = 10, scale = 2)
    private BigDecimal outputBeforeKw;

    @Column(name = "output_after_kw", precision = 10, scale = 2)
    private BigDecimal outputAfterKw;

    @Column(name = "next_service_due")
    private LocalDate nextServiceDue;

    @Column(name = "customer_signature_name")
    private String customerSignatureName;

    @Column(name = "customer_remarks", columnDefinition = "TEXT")
    private String customerRemarks;

    /** False = draft, invisible to the customer. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean published = false;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "prepared_by")
    private String preparedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "pdf_storage_key")
    private String pdfStorageKey;

    @Column(name = "pdf_generated_at")
    private LocalDateTime pdfGeneratedAt;

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** Gain in kW between the before and after readings, when both were taken. */
    @Transient
    public BigDecimal improvementKw() {
        if (outputBeforeKw == null || outputAfterKw == null) return null;
        return outputAfterKw.subtract(outputBeforeKw);
    }

    @Transient
    public String fileName() {
        return reportNumber + ".pdf";
    }
}
