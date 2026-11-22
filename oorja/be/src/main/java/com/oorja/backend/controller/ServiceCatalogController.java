package com.oorja.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customer/services")
public class ServiceCatalogController {

    private static final List<String> SERVICE_TYPES = List.of(
            "Solar Plant Cleaning",
            "Preventive Maintenance",
            "Inspection & Checkup",
            "New Solar Installation",
            "Inverter Replacement"
    );

    @GetMapping
    public List<String> getServiceTypes() {
        return SERVICE_TYPES;
    }
}
