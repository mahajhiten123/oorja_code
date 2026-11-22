package com.oorja.backend.controller;

import com.oorja.backend.dto.response.AdminMemberResponse;
import com.oorja.backend.service.AdminMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registered users ("members") with the sites/plants they've registered and
 * their bookings, split into upcoming / completed / cancelled. AMC plan
 * management will be added once real AMC data is available.
 */
@RestController
@RequestMapping("/api/admin/members")
@RequiredArgsConstructor
public class AdminMemberController {

    private final AdminMemberService adminMemberService;

    @GetMapping
    public Page<AdminMemberResponse> getAll(@PageableDefault(size = 20) Pageable pageable) {
        return adminMemberService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public AdminMemberResponse getOne(@PathVariable Long id) {
        return adminMemberService.findById(id);
    }
}
