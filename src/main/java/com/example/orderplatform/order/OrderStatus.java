package com.example.orderplatform.order;

public enum OrderStatus {
    CREATED,
    CONFIRMED,
    CANCELLED
    // More statuses (PAYMENT_PENDING, SHIPPED, DELIVERED, ...) arrive in later stages
    // once payment and shipment exist. Stage 1 only ever produces CREATED.
}
