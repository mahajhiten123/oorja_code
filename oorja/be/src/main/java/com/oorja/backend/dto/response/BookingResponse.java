package com.oorja.backend.dto.response;

import com.oorja.backend.entity.Booking;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingResponse {
    private Long id;
    private String token;
    private Long customerId;
    private String customerName;
    private String customerPhone;
    private String customerEmail;
    private Long plantId;
    private String plantName;
    private String plantCapacity;
    private String serviceType;
    private LocalDate scheduledDate;
    private LocalTime scheduledTime;
    private String status;
    private Long technicianId;
    private String technicianName;
    private String notes;
    private String location;
    private Long amountPaise;
    private String paymentRef;
    private String serviceLabel;
    private LocalDateTime createdAt;
    @Builder.Default
    private List<BookingAttachmentResponse> attachments = List.of();

    public static BookingResponse from(Booking b) {
        return from(b, List.of());
    }

    public static BookingResponse from(Booking b, List<BookingAttachmentResponse> attachments) {
        return BookingResponse.builder()
                .id(b.getId())
                .token(b.getToken())
                .customerId(b.getCustomer().getId())
                .customerName(b.getCustomer().getName())
                .customerPhone(b.getCustomer().getPhone())
                .customerEmail(b.getCustomer().getEmail())
                .plantId(b.getPlant().getId())
                .plantName(b.getPlant().getName() + " (" + b.getPlant().displayCapacity() + ")")
                .plantCapacity(b.getPlant().displayCapacity())
                .serviceType(b.getServiceType())
                .scheduledDate(b.getScheduledDate())
                .scheduledTime(b.getScheduledTime())
                .status(b.getStatus().name())
                .technicianId(b.getTechnician() != null ? b.getTechnician().getId() : null)
                .technicianName(b.getTechnician() != null ? b.getTechnician().getName() : "-")
                .notes(b.getNotes())
                .location(b.getLocation() != null ? b.getLocation() : b.getPlant().getLocation())
                .amountPaise(b.getAmountPaise())
                .paymentRef(b.getPaymentRef())
                .serviceLabel(b.getServiceLabel() != null ? b.getServiceLabel() : b.getServiceType())
                .createdAt(b.getCreatedAt())
                .attachments(attachments)
                .build();
    }
}
