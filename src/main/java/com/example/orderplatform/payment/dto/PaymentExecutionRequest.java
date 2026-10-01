package com.example.orderplatform.payment.dto;

import jakarta.validation.constraints.NotNull;

public record PaymentExecutionRequest(
        @NotNull String idempotencyKey,
        @NotNull String provider
) {}
