package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.SubjectType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One message in a support thread. Append-only: editing history in a
 * support conversation destroys the only record of what was actually
 * promised to a customer.
 */
@Entity
@Table(name = "support_messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SupportMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private SupportTicket ticket;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_type", nullable = false)
    private SubjectType authorType;

    @Column(name = "author_label")
    private String authorLabel;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /**
     * Admin-only note. Never serialised into a customer response — see the
     * filtering in SupportTicketService, which is the single place that
     * decision is made.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean internal = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
