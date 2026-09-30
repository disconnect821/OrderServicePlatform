package com.example.orderplatform.payment.dto;

import com.example.orderplatform.payment.PaymentStatus;

import java.math.BigDecimal;

public record PaymentResponse(
        PaymentStatus status,
        String message,
        BigDecimal amount
) {}
