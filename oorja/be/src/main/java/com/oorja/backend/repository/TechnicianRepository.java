package com.oorja.backend.repository;

import com.oorja.backend.entity.Technician;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TechnicianRepository extends JpaRepository<Technician, Long> {
    Optional<Technician> findByTechnicianCode(String technicianCode);

    @Query("select coalesce(max(t.id), 0) from Technician t")
    Long findMaxId();
}
