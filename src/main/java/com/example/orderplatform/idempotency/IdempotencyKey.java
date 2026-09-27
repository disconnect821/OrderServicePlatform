package com.example.orderplatform.idempotency;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {

    @Id
    @Column(name = "key", length = 255)
    private String key;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected IdempotencyKey() {}

    public IdempotencyKey(String key, Long userId, String requestHash) {
        this.key = key;
        this.userId = userId;
        this.requestHash = requestHash;
        this.createdAt = Instant.now();
        this.expiresAt = this.createdAt.plusSeconds(86400);
    }

    public String getKey() { return key; }
    public Long getUserId() { return userId; }
    public String getRequestHash() { return requestHash; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String responseJson) { this.responseJson = responseJson; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
