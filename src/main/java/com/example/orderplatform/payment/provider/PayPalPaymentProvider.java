package com.example.orderplatform.payment.provider;

import com.example.orderplatform.payment.PaymentStatus;
import com.example.orderplatform.payment.dto.PaymentRequest;
import com.example.orderplatform.payment.dto.PaymentResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class PayPalPaymentProvider implements PaymentProvider {

    @Override
    public PaymentResponse processPayment(PaymentRequest request, BigDecimal amount) {
        boolean simulatedSuccess = amount.compareTo(java.math.BigDecimal.ZERO) > 0
                && amount.compareTo(java.math.BigDecimal.valueOf(500)) < 0;
        if (simulatedSuccess) {
            return new PaymentResponse(
                    PaymentStatus.SUCCESS,
                    "Payment processed successfully via PayPal",
                    amount
            );
        } else {
            return new PaymentResponse(
                    PaymentStatus.FAILED,
                    "Payment failed via PayPal: amount out of range or invalid",
                    amount
            );
        }
    }
}
