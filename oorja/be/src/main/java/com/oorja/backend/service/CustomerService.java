package com.oorja.backend.service;

import com.oorja.backend.dto.request.UpdateCustomerProfileRequest;
import com.oorja.backend.dto.response.CustomerResponse;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.enums.AccountStatus;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.BookingRepository;
import com.oorja.backend.repository.CustomerRepository;
import com.oorja.backend.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PlantRepository plantRepository;
    private final BookingRepository bookingRepository;

    public List<CustomerResponse> findAll() {
        return customerRepository.findAll().stream().map(this::enrich).toList();
    }

    public CustomerResponse findById(Long id) {
        return enrich(getOrThrow(id));
    }

    public List<CustomerResponse> search(String q) {
        return customerRepository.search(q).stream().map(this::enrich).toList();
    }

    @Transactional
    public CustomerResponse setStatus(Long id, AccountStatus status) {
        Customer customer = getOrThrow(id);
        customer.setStatus(status);
        return enrich(customerRepository.save(customer));
    }

    /** Self-service profile update (name/phone/address) from the customer's own "My Profile" screen. */
    @Transactional
    public CustomerResponse updateOwnProfile(Long id, UpdateCustomerProfileRequest request) {
        Customer customer = getOrThrow(id);
        customer.setName(request.name());
        if (request.phone() != null) customer.setPhone(request.phone());
        if (request.address() != null) customer.setAddress(request.address());
        return enrich(customerRepository.save(customer));
    }

    private CustomerResponse enrich(Customer customer) {
        long plants = plantRepository.findByCustomerId(customer.getId()).size();
        long bookings = bookingRepository.findByCustomerIdOrderByCreatedAtDesc(customer.getId()).size();
        return CustomerResponse.from(customer, plants, bookings);
    }

    private Customer getOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Customer not found: " + id));
    }
}
