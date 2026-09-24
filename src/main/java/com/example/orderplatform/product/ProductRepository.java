package com.example.orderplatform.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    // NOTE: findById() here does a plain read with no lock.
    // Two concurrent orders can both read the same available_quantity
    // and both succeed, overselling stock. We deliberately leave this
    // unsafe for Stage 1 - pessimistic/optimistic locking is Stage 2.

    // PESSIMISTIC LOCKING: select ... for update to prevent race conditions
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdWithLock(@Param("id") Long id);
}
