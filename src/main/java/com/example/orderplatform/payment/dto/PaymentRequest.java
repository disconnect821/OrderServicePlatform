package com.example.orderplatform.payment.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotNull Long orderId,
        @NotNull String provider,
        @NotNull String idempotencyKey
) {}
