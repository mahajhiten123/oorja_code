package com.oorja.backend.dto.response;

import com.oorja.backend.entity.Customer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminMemberResponse {
    private Long id;
    private String customerCode;
    private String name;
    private String phone;
    private String email;
    private String address;
    private String status;
    private List<PlantResponse> sites;
    private MemberBookingsResponse bookings;

    public static AdminMemberResponse from(Customer c, List<PlantResponse> sites, MemberBookingsResponse bookings) {
        return AdminMemberResponse.builder()
                .id(c.getId())
                .customerCode(c.getCustomerCode())
                .name(c.getName())
                .phone(c.getPhone())
                .email(c.getEmail())
                .address(c.getAddress())
                .status(c.getStatus().name())
                .sites(sites)
                .bookings(bookings)
                .build();
    }
}
