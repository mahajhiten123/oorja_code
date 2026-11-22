package com.oorja.backend.dto.response;

import com.oorja.backend.entity.Plant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PlantResponse {
    private Long id;
    private String plantCode;
    private String name;
    private String type;
    private BigDecimal capacityKw;
    private String capacityDisplay;
    private String location;
    private String status;
    private Long customerId;
    private String customerName;
    private boolean deleted;
    private java.time.LocalDateTime deletedAt;

    public static PlantResponse from(Plant p) {
        return PlantResponse.builder()
                .id(p.getId())
                .plantCode(p.getPlantCode())
                .name(p.getName())
                .type(p.getType().name())
                .capacityKw(p.getCapacityKw())
                .capacityDisplay(p.displayCapacity())
                .location(p.getLocation())
                .status(p.getStatus().name())
                .customerId(p.getCustomer() != null ? p.getCustomer().getId() : null)
                .customerName(p.getCustomer() != null ? p.getCustomer().getName() : null)
                .deleted(p.isDeleted())
                .deletedAt(p.getDeletedAt())
                .build();
    }
}
