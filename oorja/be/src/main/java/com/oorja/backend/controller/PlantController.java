package com.oorja.backend.controller;

import com.oorja.backend.dto.request.PlantRequest;
import com.oorja.backend.dto.response.ApiMessageResponse;
import com.oorja.backend.dto.response.PlantResponse;
import com.oorja.backend.service.PlantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/plants")
@RequiredArgsConstructor
public class PlantController {

    private final PlantService plantService;

    @GetMapping
    public List<PlantResponse> getAll() {
        return plantService.findAll();
    }

    @GetMapping("/{id}")
    public PlantResponse getOne(@PathVariable Long id) {
        return plantService.findById(id);
    }

    @PostMapping
    public ResponseEntity<PlantResponse> create(@Valid @RequestBody PlantRequest request) {
        return ResponseEntity.ok(plantService.create(request));
    }

    @PutMapping("/{id}")
    public PlantResponse update(@PathVariable Long id, @Valid @RequestBody PlantRequest request) {
        return plantService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ApiMessageResponse delete(@PathVariable Long id) {
        plantService.delete(id);
        return ApiMessageResponse.ok("Plant deleted");
    }
}
