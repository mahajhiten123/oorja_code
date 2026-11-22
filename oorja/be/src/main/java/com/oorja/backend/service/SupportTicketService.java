package com.oorja.backend.service;

import com.oorja.backend.dto.request.CreateTicketRequest;
import com.oorja.backend.dto.request.TicketReplyRequest;
import com.oorja.backend.dto.response.SupportTicketResponse;
import com.oorja.backend.entity.Booking;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.SupportMessage;
import com.oorja.backend.entity.SupportTicket;
import com.oorja.backend.entity.enums.SubjectType;
import com.oorja.backend.entity.enums.SupportPriority;
import com.oorja.backend.entity.enums.SupportTicketStatus;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingRepository;
import com.oorja.backend.repository.CustomerRepository;
import com.oorja.backend.repository.SupportMessageRepository;
import com.oorja.backend.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Support ticketing: a customer raises an issue, it lands with the admin
 * team by email and in the console, and the thread stays attached to the
 * booking, invoice or refund it's about.
 *
 * Three things here do the real work:
 *
 * <ul>
 *   <li><b>A promised first response.</b> The category sets a deadline when
 *       the ticket is created. It's a column, so a missed promise is a query
 *       rather than a feeling.</li>
 *   <li><b>Unread flags on both sides.</b> A ticket nobody has read is
 *       visible as such in the queue; a reply the customer hasn't seen shows
 *       as a badge in the app.</li>
 *   <li><b>Internal notes never leak.</b> Exactly one method decides what a
 *       customer may see, and every customer-facing read goes through it.</li>
 * </ul>
 *
 * Email is best-effort throughout. A mail server being down must never stop
 * a customer from filing a ticket — the ticket is the record, the email is
 * only a notification.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SupportTicketService {

    private final SupportTicketRepository ticketRepository;
    private final SupportMessageRepository messageRepository;
    private final CustomerRepository customerRepository;
    private final BookingRepository bookingRepository;
    private final CodeGeneratorService codeGenerator;
    private final EmailService emailService;

    // ---------- Customer ----------

    @Transactional
    public SupportTicketResponse create(Long customerId, CreateTicketRequest request) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> ApiException.notFound("Customer not found."));

        Booking booking = null;
        if (request.bookingId() != null) {
            booking = bookingRepository.findById(request.bookingId())
                    .orElseThrow(() -> ApiException.notFound("Booking not found."));
            if (!booking.getCustomer().getId().equals(customerId)) {
                throw ApiException.forbidden("That booking isn't yours.");
            }
        }

        SupportPriority priority = request.category().defaultPriority();

        SupportTicket ticket = ticketRepository.save(SupportTicket.builder()
                .ticketNumber(codeGenerator.nextTicketNumber())
                .customer(customer)
                .booking(booking)
                .invoiceId(request.invoiceId())
                .refundId(request.refundId())
                .category(request.category())
                .priority(priority)
                .status(SupportTicketStatus.OPEN)
                .subject(request.subject().trim())
                .firstResponseDue(LocalDateTime.now().plusHours(request.category().firstResponseHours()))
                .unreadForAdmin(true)
                .unreadForCustomer(false)
                .build());

        addMessage(ticket, SubjectType.CUSTOMER, customer.getName(), request.message(), false);

        notifyAdmin(ticket, request.message());

        return respond(ticket, false);
    }

    @Transactional(readOnly = true)
    public List<SupportTicketResponse> findForCustomer(Long customerId) {
        return ticketRepository.findByCustomerIdOrderByLastMessageAtDesc(customerId).stream()
                .map(t -> respond(t, false))
                .toList();
    }

    /** Opening a ticket clears the customer's unread badge for it. */
    @Transactional
    public SupportTicketResponse openForCustomer(Long ticketId, Long customerId) {
        SupportTicket ticket = ownedByCustomer(ticketId, customerId);
        if (Boolean.TRUE.equals(ticket.getUnreadForCustomer())) {
            ticket.setUnreadForCustomer(false);
            ticketRepository.save(ticket);
        }
        return respond(ticket, false);
    }

    @Transactional
    public SupportTicketResponse replyAsCustomer(Long ticketId, Long customerId, TicketReplyRequest request) {
        SupportTicket ticket = ownedByCustomer(ticketId, customerId);
        if (ticket.getStatus() == SupportTicketStatus.CLOSED) {
            throw ApiException.badRequest("This ticket is closed. Raise a new one and we'll pick it up from there.");
        }

        addMessage(ticket, SubjectType.CUSTOMER, ticket.getCustomer().getName(), request.message(), false);

        // A customer replying to a resolved ticket is telling us it wasn't.
        if (ticket.getStatus() == SupportTicketStatus.RESOLVED
                || ticket.getStatus() == SupportTicketStatus.WAITING_CUSTOMER) {
            ticket.setStatus(SupportTicketStatus.IN_PROGRESS);
            ticket.setResolvedAt(null);
        }
        ticket.setUnreadForAdmin(true);
        ticketRepository.save(ticket);

        notifyAdmin(ticket, request.message());
        return respond(ticket, false);
    }

    @Transactional
    public SupportTicketResponse closeAsCustomer(Long ticketId, Long customerId) {
        SupportTicket ticket = ownedByCustomer(ticketId, customerId);
        ticket.setStatus(SupportTicketStatus.CLOSED);
        ticket.setClosedAt(LocalDateTime.now());
        addMessage(ticket, SubjectType.CUSTOMER, ticket.getCustomer().getName(),
                "Closed by the customer.", false);
        return respond(ticketRepository.save(ticket), false);
    }

    // ---------- Admin ----------

    @Transactional(readOnly = true)
    public List<SupportTicketResponse> findAllForAdmin() {
        return ticketRepository.findAllByOrderByLastMessageAtDesc().stream().map(t -> respond(t, true)).toList();
    }

    @Transactional(readOnly = true)
    public List<SupportTicketResponse> findQueueForAdmin() {
        return ticketRepository.findByStatusInOrderByLastMessageAtDesc(List.of(
                        SupportTicketStatus.OPEN, SupportTicketStatus.IN_PROGRESS, SupportTicketStatus.WAITING_CUSTOMER))
                .stream().map(t -> respond(t, true)).toList();
    }

    @Transactional
    public SupportTicketResponse openForAdmin(Long ticketId) {
        SupportTicket ticket = get(ticketId);
        if (Boolean.TRUE.equals(ticket.getUnreadForAdmin())) {
            ticket.setUnreadForAdmin(false);
            ticketRepository.save(ticket);
        }
        return respond(ticket, true);
    }

    @Transactional
    public SupportTicketResponse replyAsAdmin(Long ticketId, TicketReplyRequest request, String adminLabel) {
        SupportTicket ticket = get(ticketId);
        boolean internal = Boolean.TRUE.equals(request.internal());

        addMessage(ticket, SubjectType.ADMIN, adminLabel, request.message(), internal);

        if (!internal) {
            // The SLA clock stops on the first reply the customer can actually read.
            if (ticket.getFirstResponseAt() == null) {
                ticket.setFirstResponseAt(LocalDateTime.now());
            }
            ticket.setStatus(SupportTicketStatus.WAITING_CUSTOMER);
            ticket.setUnreadForCustomer(true);
            notifyCustomer(ticket, request.message(), adminLabel);
        }
        ticket.setUnreadForAdmin(false);
        if (ticket.getAssignedTo() == null) {
            ticket.setAssignedTo(adminLabel);
        }
        return respond(ticketRepository.save(ticket), true);
    }

    @Transactional
    public SupportTicketResponse updateStatus(Long ticketId, SupportTicketStatus status,
                                              SupportPriority priority, String assignee,
                                              String resolutionNote, String adminLabel) {
        SupportTicket ticket = get(ticketId);

        if (priority != null) ticket.setPriority(priority);
        if (assignee != null && !assignee.isBlank()) ticket.setAssignedTo(assignee);

        if (status != null && status != ticket.getStatus()) {
            ticket.setStatus(status);
            if (status == SupportTicketStatus.RESOLVED) {
                ticket.setResolvedAt(LocalDateTime.now());
                ticket.setResolutionNote(resolutionNote);
                ticket.setUnreadForCustomer(true);
                addMessage(ticket, SubjectType.ADMIN, adminLabel,
                        resolutionNote == null || resolutionNote.isBlank()
                                ? "Marked as resolved." : resolutionNote, false);
                notifyCustomer(ticket, "Your ticket has been marked as resolved."
                        + (resolutionNote == null || resolutionNote.isBlank() ? "" : "\n\n" + resolutionNote), adminLabel);
            } else if (status == SupportTicketStatus.CLOSED) {
                ticket.setClosedAt(LocalDateTime.now());
            }
        }
        return respond(ticketRepository.save(ticket), true);
    }

    /** Open tickets past their promised first reply — the queue that should never have rows. */
    @Transactional(readOnly = true)
    public List<SupportTicketResponse> findBreached() {
        return ticketRepository.findSlaBreached().stream().map(t -> respond(t, true)).toList();
    }

    // ---------- Internals ----------

    private SupportMessage addMessage(SupportTicket ticket, SubjectType authorType, String label,
                                      String body, boolean internal) {
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("Write a message before sending.");
        }
        SupportMessage message = messageRepository.save(SupportMessage.builder()
                .ticket(ticket)
                .authorType(authorType)
                .authorLabel(label)
                .body(body.trim())
                .internal(internal)
                .build());

        if (!internal) {
            ticket.setLastMessageAt(LocalDateTime.now());
        }
        return message;
    }

    /**
     * The single place that decides what a customer may see. Every
     * customer-facing read goes through here with {@code includeInternal}
     * false, so an internal note can only ever leak by adding a second path
     * — which is exactly the change a reviewer would catch.
     */
    private SupportTicketResponse respond(SupportTicket ticket, boolean includeInternal) {
        List<SupportMessage> messages = includeInternal
                ? messageRepository.findByTicketIdOrderByCreatedAtAsc(ticket.getId())
                : messageRepository.findByTicketIdAndInternalFalseOrderByCreatedAtAsc(ticket.getId());
        return SupportTicketResponse.from(ticket, messages);
    }

    private SupportTicket get(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Ticket not found: " + id));
    }

    private SupportTicket ownedByCustomer(Long ticketId, Long customerId) {
        SupportTicket ticket = get(ticketId);
        if (!ticket.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("That ticket isn't yours.");
        }
        return ticket;
    }

    private void notifyAdmin(SupportTicket ticket, String message) {
        try {
            emailService.sendSupportTicketToAdmin(
                    ticket.getTicketNumber(),
                    ticket.getCategory().label(),
                    ticket.getPriority().name(),
                    ticket.getSubject(),
                    ticket.getCustomer().getName(),
                    ticket.getCustomer().getEmail(),
                    ticket.getCustomer().getPhone(),
                    ticket.getBooking() != null ? ticket.getBooking().getToken() : null,
                    message);
        } catch (Exception e) {
            // The ticket is already saved and visible in the console; a mail
            // failure must not fail the customer's request.
            log.error("Couldn't email the support team about {}: {}", ticket.getTicketNumber(), e.getMessage());
        }
    }

    private void notifyCustomer(SupportTicket ticket, String message, String adminLabel) {
        if (ticket.getCustomer().getEmail() == null || ticket.getCustomer().getEmail().isBlank()) return;
        try {
            emailService.sendSupportReplyToCustomer(
                    ticket.getCustomer().getEmail(),
                    ticket.getCustomer().getName(),
                    ticket.getTicketNumber(),
                    ticket.getSubject(),
                    message,
                    adminLabel);
        } catch (Exception e) {
            log.error("Couldn't email the customer about {}: {}", ticket.getTicketNumber(), e.getMessage());
        }
    }
}
