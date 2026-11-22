package com.oorja.backend.controller;

import com.oorja.backend.dto.request.TicketReplyRequest;
import com.oorja.backend.dto.request.UpdateTicketRequest;
import com.oorja.backend.dto.response.SupportTicketResponse;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.SupportTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Admin console → Support. */
@RestController
@RequestMapping("/api/admin/support")
@RequiredArgsConstructor
public class AdminSupportController {

    private final SupportTicketService ticketService;

    @GetMapping("/tickets")
    public List<SupportTicketResponse> all() {
        return ticketService.findAllForAdmin();
    }

    /** Only tickets still needing something from us. */
    @GetMapping("/tickets/queue")
    public List<SupportTicketResponse> queue() {
        return ticketService.findQueueForAdmin();
    }

    /** Open tickets past the first-response time we promised. Should be empty. */
    @GetMapping("/tickets/breached")
    public List<SupportTicketResponse> breached() {
        return ticketService.findBreached();
    }

    @GetMapping("/tickets/{id}")
    public SupportTicketResponse one(@PathVariable Long id) {
        return ticketService.openForAdmin(id);
    }

    @PostMapping("/tickets/{id}/reply")
    public SupportTicketResponse reply(@PathVariable Long id, @Valid @RequestBody TicketReplyRequest request) {
        return ticketService.replyAsAdmin(id, request, actor());
    }

    /** Triage: status, priority, assignee, resolution note. Send only what changes. */
    @PostMapping("/tickets/{id}/triage")
    public SupportTicketResponse update(@PathVariable Long id, @Valid @RequestBody UpdateTicketRequest request) {
        return ticketService.updateStatus(id, request.status(), request.priority(),
                request.assignedTo(), request.resolutionNote(), actor());
    }

    private String actor() {
        var user = CurrentUser.get();
        return user != null && user.email() != null ? user.email() : "admin";
    }
}
