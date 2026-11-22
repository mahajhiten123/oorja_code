package com.oorja.backend.controller;

import com.oorja.backend.dto.response.CustomerResponse;
import com.oorja.backend.entity.enums.AccountStatus;
import com.oorja.backend.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public List<CustomerResponse> getAll(@RequestParam(required = false) String q) {
        if (q != null && !q.isBlank()) {
            return customerService.search(q);
        }
        return customerService.findAll();
    }

    @GetMapping("/{id}")
    public CustomerResponse getOne(@PathVariable Long id) {
        return customerService.findById(id);
    }

    @PatchMapping("/{id}/status")
    public CustomerResponse setStatus(@PathVariable Long id, @RequestParam AccountStatus status) {
        return customerService.setStatus(id, status);
    }
}
