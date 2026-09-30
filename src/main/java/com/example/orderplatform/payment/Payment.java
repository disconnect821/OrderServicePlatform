package com.example.orderplatform.payment;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Column(name = "result_message")
    private String resultMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
    }

    public Payment(Long orderId, BigDecimal amount, PaymentStatus status, String providerName, String resultMessage) {
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
        this.providerName = providerName;
        this.resultMessage = resultMessage;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getResultMessage() {
        return resultMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setStatus(PaymentStatus status) { this.status = status; }
    public void setResultMessage(String resultMessage) { this.resultMessage = resultMessage; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
