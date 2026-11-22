package com.oorja.backend.dto.response;

import com.oorja.backend.entity.SupportMessage;
import com.oorja.backend.entity.SupportTicket;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A ticket and its thread. Which messages are in {@code messages} is decided
 * by SupportTicketService — internal notes are filtered out before this
 * object is ever built, not hidden by the client.
 */
@Data
@Builder
public class SupportTicketResponse {

    private Long id;
    private String ticketNumber;
    private String subject;
    private String category;
    private String categoryLabel;
    private String priority;
    private String status;
    private String statusLabel;
    private boolean open;

    private Long customerId;
    private String customerName;
    private String customerEmail;
    private String customerPhone;

    private Long bookingId;
    private String bookingToken;
    private Long invoiceId;
    private Long refundId;

    private String assignedTo;
    private LocalDateTime firstResponseDue;
    private LocalDateTime firstResponseAt;
    private boolean slaBreached;
    private LocalDateTime resolvedAt;
    private String resolutionNote;

    private boolean unreadForAdmin;
    private boolean unreadForCustomer;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;

    private List<Message> messages;

    @Data
    @Builder
    public static class Message {
        private Long id;
        private String authorType;
        private String authorLabel;
        private String body;
        private boolean internal;
        private LocalDateTime at;
    }

    public static SupportTicketResponse from(SupportTicket t, List<SupportMessage> messages) {
        return SupportTicketResponse.builder()
                .id(t.getId())
                .ticketNumber(t.getTicketNumber())
                .subject(t.getSubject())
                .category(t.getCategory().name())
                .categoryLabel(t.getCategory().label())
                .priority(t.getPriority().name())
                .status(t.getStatus().name())
                .statusLabel(t.getStatus().label())
                .open(t.getStatus().isOpen())
                .customerId(t.getCustomer().getId())
                .customerName(t.getCustomer().getName())
                .customerEmail(t.getCustomer().getEmail())
                .customerPhone(t.getCustomer().getPhone())
                .bookingId(t.getBooking() != null ? t.getBooking().getId() : null)
                .bookingToken(t.getBooking() != null ? t.getBooking().getToken() : null)
                .invoiceId(t.getInvoiceId())
                .refundId(t.getRefundId())
                .assignedTo(t.getAssignedTo())
                .firstResponseDue(t.getFirstResponseDue())
                .firstResponseAt(t.getFirstResponseAt())
                .slaBreached(t.isSlaBreached())
                .resolvedAt(t.getResolvedAt())
                .resolutionNote(t.getResolutionNote())
                .unreadForAdmin(Boolean.TRUE.equals(t.getUnreadForAdmin()))
                .unreadForCustomer(Boolean.TRUE.equals(t.getUnreadForCustomer()))
                .lastMessageAt(t.getLastMessageAt())
                .createdAt(t.getCreatedAt())
                .messages(messages == null ? List.of() : messages.stream()
                        .map(m -> Message.builder()
                                .id(m.getId())
                                .authorType(m.getAuthorType().name())
                                .authorLabel(m.getAuthorLabel())
                                .body(m.getBody())
                                .internal(Boolean.TRUE.equals(m.getInternal()))
                                .at(m.getCreatedAt())
                                .build())
                        .toList())
                .build();
    }
}
