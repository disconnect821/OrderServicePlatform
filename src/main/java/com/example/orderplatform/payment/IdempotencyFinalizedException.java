package com.example.orderplatform.payment;

import com.example.orderplatform.payment.dto.PaymentResponse;

public class IdempotencyFinalizedException extends RuntimeException {
    private final PaymentResponse cachedResponse;

    public IdempotencyFinalizedException(PaymentResponse cachedResponse) {
        super("Idempotency key finalized: " + cachedResponse);
        this.cachedResponse = cachedResponse;
    }

    public PaymentResponse getCachedResponse() {
        return cachedResponse;
    }
}
