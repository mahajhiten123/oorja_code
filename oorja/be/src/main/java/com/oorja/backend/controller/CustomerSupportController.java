package com.oorja.backend.controller;

import com.oorja.backend.dto.request.CreateTicketRequest;
import com.oorja.backend.dto.request.TicketReplyRequest;
import com.oorja.backend.dto.response.SupportTicketResponse;
import com.oorja.backend.entity.enums.SupportCategory;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.SupportTicketService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/** The customer's support inbox. */
@RestController
@RequestMapping("/api/customer/support")
@RequiredArgsConstructor
public class CustomerSupportController {

    private final SupportTicketService ticketService;

    /** Category list with the response time we promise for each — rendered in the form. */
    @GetMapping("/categories")
    public List<CategoryOption> categories() {
        return Arrays.stream(SupportCategory.values())
                .map(c -> new CategoryOption(c.name(), c.label(), c.firstResponseHours()))
                .toList();
    }

    public record CategoryOption(String id, String label, int firstResponseHours) {}

    @GetMapping("/tickets")
    public List<SupportTicketResponse> myTickets() {
        return ticketService.findForCustomer(CurrentUser.get().userId());
    }

    @PostMapping("/tickets")
    public SupportTicketResponse create(@Valid @RequestBody CreateTicketRequest request) {
        return ticketService.create(CurrentUser.get().userId(), request);
    }

    @GetMapping("/tickets/{id}")
    public SupportTicketResponse one(@PathVariable Long id) {
        return ticketService.openForCustomer(id, CurrentUser.get().userId());
    }

    @PostMapping("/tickets/{id}/reply")
    public SupportTicketResponse reply(@PathVariable Long id, @Valid @RequestBody TicketReplyRequest request) {
        // Customers can never post an internal note, whatever the body says.
        return ticketService.replyAsCustomer(id, CurrentUser.get().userId(),
                new TicketReplyRequest(request.message(), false));
    }

    @PostMapping("/tickets/{id}/close")
    public SupportTicketResponse close(@PathVariable Long id) {
        return ticketService.closeAsCustomer(id, CurrentUser.get().userId());
    }
}
