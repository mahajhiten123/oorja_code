package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.SupportCategory;
import com.oorja.backend.entity.enums.SupportPriority;
import com.oorja.backend.entity.enums.SupportTicketStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A support conversation.
 *
 * Two things here are worth more than the rest of the schema combined:
 * {@code firstResponseDue}, which turns "we'll get back to you" into a
 * measurable promise, and the two unread flags, which are what stop a
 * ticket from sitting silently in a queue nobody is watching.
 *
 * Context is attached by reference — a ticket can point at the booking,
 * invoice or refund it's about, so an admin opening it doesn't have to go
 * hunting for what the customer is actually talking about.
 */
@Entity
@Table(name = "support_tickets")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportTicket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** TKT-26-00231 — quoted in every email about this ticket. */
    @Column(name = "ticket_number", nullable = false, unique = true, updatable = false)
    private String ticketNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(name = "invoice_id")
    private Long invoiceId;

    @Column(name = "refund_id")
    private Long refundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SupportCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SupportPriority priority = SupportPriority.NORMAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SupportTicketStatus status = SupportTicketStatus.OPEN;

    @Column(nullable = false)
    private String subject;

    @Column(name = "assigned_to")
    private String assignedTo;

    /** When we told the customer they'd hear from a human. */
    @Column(name = "first_response_due")
    private LocalDateTime firstResponseDue;

    @Column(name = "first_response_at")
    private LocalDateTime firstResponseAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "resolution_note", columnDefinition = "TEXT")
    private String resolutionNote;

    @Column(name = "last_message_at", nullable = false)
    @Builder.Default
    private LocalDateTime lastMessageAt = LocalDateTime.now();

    @Column(name = "unread_for_admin", nullable = false)
    @Builder.Default
    private Boolean unreadForAdmin = true;

    @Column(name = "unread_for_customer", nullable = false)
    @Builder.Default
    private Boolean unreadForCustomer = false;

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

    /** True when we've blown the response time we promised and nobody has replied. */
    @Transient
    public boolean isSlaBreached() {
        return firstResponseAt == null
                && firstResponseDue != null
                && status.isOpen()
                && LocalDateTime.now().isAfter(firstResponseDue);
    }
}
