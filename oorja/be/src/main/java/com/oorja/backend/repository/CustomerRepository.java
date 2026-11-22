package com.oorja.backend.repository;

import com.oorja.backend.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    Optional<Customer> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
    Optional<Customer> findByCustomerCode(String customerCode);
    Optional<Customer> findByPhone(String phone);
    boolean existsByPhone(String phone);
    Optional<Customer> findByGoogleId(String googleId);

    @Query("select c from Customer c where lower(c.name) like lower(concat('%', :q, '%')) " +
           "or lower(c.email) like lower(concat('%', :q, '%')) or c.phone like concat('%', :q, '%')")
    List<Customer> search(String q);

    @Query("select coalesce(max(c.id), 0) from Customer c")
    Long findMaxId();
}
