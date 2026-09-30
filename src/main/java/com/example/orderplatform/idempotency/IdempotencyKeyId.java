package com.example.orderplatform.idempotency;

import jakarta.persistence.*;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class IdempotencyKeyId implements Serializable {

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", length = 32, nullable = false)
    private IdempotencyOperationType operationType;

    @Column(name = "key", length = 255, nullable = false)
    private String key;

    public IdempotencyKeyId() {}

    public IdempotencyKeyId(IdempotencyOperationType operationType, String key) {
        this.operationType = operationType;
        this.key = key;
    }

    public IdempotencyOperationType getOperationType() { return operationType; }
    public String getKey() { return key; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IdempotencyKeyId that)) return false;
        return Objects.equals(operationType, that.operationType) && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(operationType, key);
    }
}
