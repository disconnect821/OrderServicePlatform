package com.example.orderplatform.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // Explicit JOIN FETCH avoids the classic N+1 problem: without this,
    // Hibernate would issue one query for the order and then one more
    // per item to lazily load each product.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdWithLock(@Param("id") Long id);

    @Query("""
            select distinct o from Order o
            left join fetch o.items i
            left join fetch i.product
            where o.id = :id
            """)
    Optional<Order> findByIdWithItems(@Param("id") Long id);
}
