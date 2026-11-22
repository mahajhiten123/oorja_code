package com.oorja.backend.entity;

import com.oorja.backend.entity.enums.PlantStatus;
import com.oorja.backend.entity.enums.PlantType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "plants")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Plant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Public-facing code, e.g. PLT01 */
    @Column(name = "plant_code", nullable = false, unique = true)
    private String plantCode;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlantType type;

    @Column(name = "capacity_kw", nullable = false, precision = 10, scale = 2)
    private BigDecimal capacityKw;

    @Column(nullable = false)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private PlantStatus status = PlantStatus.ACTIVE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    /**
     * Soft delete only — a plant is never physically removed, because
     * existing bookings against it must keep showing correctly in the
     * customer's booking history even after they "remove" it.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    public String displayCapacity() {
        return capacityKw.stripTrailingZeros().toPlainString() + " kW";
    }
}
