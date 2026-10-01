package com.example.orderplatform.payment.dto;

import java.math.BigDecimal;

public record PaymentExecutionContext(
        Long paymentId,
        Long orderId,
        String idempotencyKey,
        String provider,
        BigDecimal amount
) {}
