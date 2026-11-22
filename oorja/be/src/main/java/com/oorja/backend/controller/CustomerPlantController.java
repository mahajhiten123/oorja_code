package com.oorja.backend.controller;

import com.oorja.backend.dto.request.CustomerPlantRequest;
import com.oorja.backend.dto.response.ApiMessageResponse;
import com.oorja.backend.dto.response.PlantResponse;
import com.oorja.backend.security.CurrentUser;
import com.oorja.backend.service.PlantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/customer/plants")
@RequiredArgsConstructor
public class CustomerPlantController {

    private final PlantService plantService;

    /**
     * By default returns only active (non-archived) plants - what the
     * booking flow should offer to pick from. Pass includeArchived=true for
     * the "My Plants" management screen, which also shows archived sites
     * (their booking history keeps working either way).
     */
    @GetMapping
    public List<PlantResponse> myPlants(@RequestParam(defaultValue = "false") boolean includeArchived) {
        return plantService.findByCustomer(CurrentUser.get().userId(), includeArchived);
    }

    /** Lets a customer register a new plant/site of their own, e.g. from the "Add a plant" modal. */
    @PostMapping
    public ResponseEntity<PlantResponse> add(@Valid @RequestBody CustomerPlantRequest request) {
        return ResponseEntity.ok(plantService.createForCustomer(CurrentUser.get().userId(), request));
    }

    @PutMapping("/{id}")
    public PlantResponse update(@PathVariable Long id, @Valid @RequestBody CustomerPlantRequest request) {
        return plantService.updateForCustomer(CurrentUser.get().userId(), id, request);
    }

    /** Soft delete only - existing bookings against this plant remain visible in booking history. */
    @DeleteMapping("/{id}")
    public ApiMessageResponse delete(@PathVariable Long id) {
        plantService.deleteForCustomer(CurrentUser.get().userId(), id);
        return ApiMessageResponse.ok("Plant removed");
    }
}
