package com.oorja.backend.dto.response;

import com.oorja.backend.entity.Customer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerResponse {
    private Long id;
    private String customerCode;
    private String name;
    private String phone;
    private String email;
    private String address;
    private String status;
    private long plantsCount;
    private long bookingsCount;

    public static CustomerResponse from(Customer c, long plantsCount, long bookingsCount) {
        return CustomerResponse.builder()
                .id(c.getId())
                .customerCode(c.getCustomerCode())
                .name(c.getName())
                .phone(c.getPhone())
                .email(c.getEmail())
                .address(c.getAddress())
                .status(c.getStatus().name())
                .plantsCount(plantsCount)
                .bookingsCount(bookingsCount)
                .build();
    }
}
