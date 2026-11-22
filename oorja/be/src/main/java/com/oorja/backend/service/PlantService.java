package com.oorja.backend.service;

import com.oorja.backend.dto.request.CustomerPlantRequest;
import com.oorja.backend.dto.request.PlantRequest;
import com.oorja.backend.dto.response.PlantResponse;
import com.oorja.backend.entity.Customer;
import com.oorja.backend.entity.Plant;
import com.oorja.backend.entity.enums.PlantStatus;
import com.oorja.backend.entity.enums.PlantType;
import com.oorja.backend.exception.ApiException;
import com.oorja.backend.repository.CustomerRepository;
import com.oorja.backend.repository.PlantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PlantService {

    private final PlantRepository plantRepository;
    private final CustomerRepository customerRepository;
    private final CodeGeneratorService codeGeneratorService;

    @Transactional(readOnly = true)
    public List<PlantResponse> findAll() {
        return plantRepository.findAll().stream().map(PlantResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PlantResponse findById(Long id) {
        return PlantResponse.from(getOrThrow(id));
    }

    /**
     * @param includeArchived false returns only the customer's active (non-deleted) plants -
     *                        used wherever a plant needs to be picked to book a service against.
     *                        true returns everything, active and archived, for the "My Plants"
     *                        management screen (archived plants still show their booking history).
     */
    @Transactional(readOnly = true)
    public List<PlantResponse> findByCustomer(Long customerId, boolean includeArchived) {
        List<Plant> plants = includeArchived
                ? plantRepository.findByCustomerId(customerId)
                : plantRepository.findByCustomerIdAndDeletedFalse(customerId);
        return plants.stream().map(PlantResponse::from).toList();
    }

    @Transactional
    public PlantResponse create(PlantRequest request) {
        Customer customer = null;
        if (request.customerId() != null) {
            customer = customerRepository.findById(request.customerId())
                    .orElseThrow(() -> ApiException.notFound("Customer not found"));
        }

        Plant plant = Plant.builder()
                .plantCode(codeGeneratorService.nextPlantCode())
                .name(request.name())
                .type(request.type())
                .capacityKw(request.capacityKw())
                .location(request.location())
                .status(request.status() != null ? request.status() : PlantStatus.ACTIVE)
                .customer(customer)
                .build();

        return PlantResponse.from(plantRepository.save(plant));
    }

    /** Customer self-service: register a plant/site under their own account. */
    @Transactional
    public PlantResponse createForCustomer(Long customerId, CustomerPlantRequest request) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> ApiException.notFound("Customer not found"));

        Plant plant = Plant.builder()
                .plantCode(codeGeneratorService.nextPlantCode())
                .name(request.name())
                .type(request.type())
                .capacityKw(request.capacityKw())
                .location(request.location())
                .status(PlantStatus.ACTIVE)
                .customer(customer)
                .build();

        return PlantResponse.from(plantRepository.save(plant));
    }

    /** Customer self-service: edit their own plant's details. */
    @Transactional
    public PlantResponse updateForCustomer(Long customerId, Long plantId, CustomerPlantRequest request) {
        Plant plant = getOwnedByOrThrow(plantId, customerId);
        if (plant.isDeleted()) {
            throw ApiException.badRequest("This plant has been removed and can no longer be edited.");
        }
        plant.setName(request.name());
        plant.setType(request.type());
        plant.setCapacityKw(request.capacityKw());
        plant.setLocation(request.location());
        return PlantResponse.from(plantRepository.save(plant));
    }

    /**
     * Customer self-service: soft delete only. The plant row (and every
     * booking that ever referenced it) is kept intact so booking history
     * keeps rendering correctly - it's just hidden from the "active" plant
     * list and can no longer be picked for new bookings.
     */
    @Transactional
    public void deleteForCustomer(Long customerId, Long plantId) {
        Plant plant = getOwnedByOrThrow(plantId, customerId);
        if (plant.isDeleted()) return;
        plant.setDeleted(true);
        plant.setDeletedAt(LocalDateTime.now());
        plantRepository.save(plant);
    }

    @Transactional
    public PlantResponse update(Long id, PlantRequest request) {
        Plant plant = getOrThrow(id);

        plant.setName(request.name());
        plant.setType(request.type());
        plant.setCapacityKw(request.capacityKw());
        plant.setLocation(request.location());
        if (request.status() != null) {
            plant.setStatus(request.status());
        }
        if (request.customerId() != null) {
            Customer customer = customerRepository.findById(request.customerId())
                    .orElseThrow(() -> ApiException.notFound("Customer not found"));
            plant.setCustomer(customer);
        }

        return PlantResponse.from(plantRepository.save(plant));
    }

    /**
     * The storefront booking flow doesn't ask customers to register a plant
     * up front (that's normally done during a site survey). So that booking
     * a service still has a real Plant to attach to, we reuse the
     * customer's first *active* plant if they have one, or auto-provision a
     * starter placeholder plant (to be corrected by ops during the actual
     * site visit) the first time they book.
     */
    @Transactional
    public Plant findOrCreateDefaultForCustomer(Customer customer, String location) {
        return plantRepository.findByCustomerIdAndDeletedFalse(customer.getId()).stream().findFirst()
                .orElseGet(() -> plantRepository.save(Plant.builder()
                        .plantCode(codeGeneratorService.nextPlantCode())
                        .name(customer.getName() + "'s Solar Plant")
                        .type(PlantType.RESIDENTIAL)
                        .capacityKw(new java.math.BigDecimal("5.00"))
                        .location(location != null && !location.isBlank() ? location : "To be confirmed")
                        .status(PlantStatus.ACTIVE)
                        .customer(customer)
                        .build()));
    }

    /** Admin: same soft-delete semantics as the customer path - plants are archived, never hard-deleted. */
    @Transactional
    public void delete(Long id) {
        Plant plant = getOrThrow(id);
        if (plant.isDeleted()) return;
        plant.setDeleted(true);
        plant.setDeletedAt(LocalDateTime.now());
        plantRepository.save(plant);
    }

    Plant getOrThrow(Long id) {
        return plantRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Plant not found: " + id));
    }

    private Plant getOwnedByOrThrow(Long plantId, Long customerId) {
        Plant plant = getOrThrow(plantId);
        if (plant.getCustomer() == null || !plant.getCustomer().getId().equals(customerId)) {
            throw ApiException.forbidden("This plant does not belong to your account.");
        }
        return plant;
    }
}
