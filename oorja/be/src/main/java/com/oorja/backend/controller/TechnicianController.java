package com.oorja.backend.controller;

import com.oorja.backend.dto.request.SetTechnicianStatusRequest;
import com.oorja.backend.dto.request.TechnicianRequest;
import com.oorja.backend.dto.response.ApiMessageResponse;
import com.oorja.backend.dto.response.TechnicianResponse;
import com.oorja.backend.service.TechnicianService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/technicians")
@RequiredArgsConstructor
public class TechnicianController {

    private final TechnicianService technicianService;

    @GetMapping
    public List<TechnicianResponse> getAll() {
        return technicianService.findAll();
    }

    @GetMapping("/{id}")
    public TechnicianResponse getOne(@PathVariable Long id) {
        return technicianService.findById(id);
    }

    @PostMapping
    public ResponseEntity<TechnicianResponse> create(@Valid @RequestBody TechnicianRequest request) {
        return ResponseEntity.ok(technicianService.create(request));
    }

    @PutMapping("/{id}")
    public TechnicianResponse update(@PathVariable Long id, @Valid @RequestBody TechnicianRequest request) {
        return technicianService.update(id, request);
    }

    /** Admin toggles a technician AVAILABLE/OFF_DUTY. "ON_JOB" is computed from active bookings. */
    @PostMapping("/{id}/status")
    public TechnicianResponse setStatus(@PathVariable Long id, @Valid @RequestBody SetTechnicianStatusRequest request) {
        return technicianService.setStatus(id, request.status());
    }

    @DeleteMapping("/{id}")
    public ApiMessageResponse delete(@PathVariable Long id) {
        technicianService.delete(id);
        return ApiMessageResponse.ok("Technician deleted");
    }
}
