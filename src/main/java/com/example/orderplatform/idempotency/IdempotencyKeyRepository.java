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
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {

    // Atomic insert for avoiding two first new requests with same operation+key
    @Modifying(flushAutomatically = true)
    @Query(value = """
    INSERT INTO idempotency_key (operation_type, \"key\", user_id, request_hash, response_json)
    VALUES (:operationType, :key, :userId, :requestHash, NULL)
    ON CONFLICT (operation_type, \"key\") DO NOTHING
    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("key") String key,
            @Param("operationType") String operationType,
            @Param("userId") Long userId,
            @Param("requestHash") String requestHash
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from IdempotencyKey i where i.id.operationType = :operationType and i.id.key = :key")
    Optional<IdempotencyKey> findByOperationAndKeyWithLock(
            @Param("operationType") IdempotencyOperationType operationType,
            @Param("key") String key);
}
