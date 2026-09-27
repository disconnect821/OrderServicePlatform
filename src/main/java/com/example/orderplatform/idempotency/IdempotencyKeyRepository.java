package com.example.orderplatform.idempotency;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, String> {

    //Atomic insert for avoiding two first new request with same key
    @Modifying(flushAutomatically = true)
    @Query(value = """
    INSERT INTO idempotency_key (key, user_id, request_hash, response_json)
    VALUES (:key, :userId, :requestHash, NULL)
    ON CONFLICT (key) DO NOTHING
    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("key") String key,
            @Param("userId") Long userId,
            @Param("requestHash") String requestHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from IdempotencyKey i where i.key = :key")
    Optional<IdempotencyKey> findByKeyWithLock(@Param("key") String key);
}
