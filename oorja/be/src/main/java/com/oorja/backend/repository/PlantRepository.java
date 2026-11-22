package com.oorja.backend.repository;

import com.oorja.backend.entity.Plant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PlantRepository extends JpaRepository<Plant, Long> {
    Optional<Plant> findByPlantCode(String plantCode);
    List<Plant> findByCustomerId(Long customerId);
    List<Plant> findByCustomerIdAndDeletedFalse(Long customerId);
    boolean existsByPlantCode(String plantCode);

    @org.springframework.data.jpa.repository.Query("select coalesce(max(p.id), 0) from Plant p")
    Long findMaxId();
}
