package com.example.orderplatform.idempotency;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {

    @EmbeddedId
    private IdempotencyKeyId id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32)
    private IdempotencyStatus status;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "resource_id")
    private Long resourceId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected IdempotencyKey() {}

    public IdempotencyKey(String key, IdempotencyOperationType operationType, Long userId, String requestHash) {
        this.id = new IdempotencyKeyId(operationType, key);
        this.userId = userId;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plusSeconds(86400);
    }

    public String getKey() { return id != null ? id.getKey() : null; }
    public IdempotencyOperationType getOperationType() { return id != null ? id.getOperationType() : null; }
    public void setOperationType(IdempotencyOperationType operationType) {
        if (this.id == null) this.id = new IdempotencyKeyId(operationType, this.getKey());
        else {
            this.id = new IdempotencyKeyId(operationType, this.getKey());
        }
    }
    public Long getUserId() { return userId; }
    public String getRequestHash() { return requestHash; }
    public IdempotencyStatus getStatus() { return status; }
    public void setStatus(IdempotencyStatus status) { this.status = status; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public Long getResourceId() { return resourceId; }
    public void setResourceId(Long resourceId) { this.resourceId = resourceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
