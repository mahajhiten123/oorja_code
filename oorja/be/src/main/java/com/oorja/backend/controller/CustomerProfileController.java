package com.oorja.backend.controller;

import com.oorja.backend.dto.request.UpdateCustomerProfileRequest;
import com.oorja.backend.dto.response.CustomerResponse;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customer/me")
@RequiredArgsConstructor
public class CustomerProfileController {

    private final CustomerService customerService;

    @GetMapping
    public CustomerResponse me() {
        return customerService.findById(CurrentUser.get().userId());
    }

    @PutMapping
    public CustomerResponse updateMe(@Valid @RequestBody UpdateCustomerProfileRequest request) {
        return customerService.updateOwnProfile(CurrentUser.get().userId(), request);
    }
}
