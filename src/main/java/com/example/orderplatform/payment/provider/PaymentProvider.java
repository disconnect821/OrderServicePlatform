package com.example.orderplatform.payment.provider;

import com.example.orderplatform.payment.dto.PaymentRequest;
import com.example.orderplatform.payment.dto.PaymentResponse;

import java.math.BigDecimal;

public interface PaymentProvider {
    PaymentResponse processPayment( PaymentRequest request, BigDecimal amount);
}
