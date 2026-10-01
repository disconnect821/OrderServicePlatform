package com.example.orderplatform.payment;

import com.example.orderplatform.idempotency.IdempotencyKey;
import com.example.orderplatform.idempotency.IdempotencyOperationType;
import com.example.orderplatform.idempotency.IdempotencyKeyRepository;
import com.example.orderplatform.payment.dto.PaymentExecutionRequest;
import com.example.orderplatform.payment.dto.PaymentRequest;
import com.example.orderplatform.payment.dto.PaymentResponse;
import com.example.orderplatform.payment.provider.PaymentProvider;
import com.example.orderplatform.payment.provider.PaymentProviderFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentExecutionService {

    private final PaymentProviderFactory providerFactory;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PaymentRepository paymentRepository;

    public PaymentExecutionService(PaymentProviderFactory providerFactory,
                                  IdempotencyKeyRepository idempotencyKeyRepository,
                                  PaymentRepository paymentRepository) {
        this.providerFactory = providerFactory;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.paymentRepository = paymentRepository;
    }

    public PaymentResponse execute(PaymentExecutionRequest request) {
        // Read idempotency record without pessimistic lock (execution is non-transactional)
        IdempotencyKey keyRecord = idempotencyKeyRepository
                .findById(new com.example.orderplatform.idempotency.IdempotencyKeyId(
                        IdempotencyOperationType.PROCESS_PAYMENT, request.idempotencyKey()))
                .orElseThrow(() -> new IllegalArgumentException("Idempotency key not found: " + request.idempotencyKey()));

        // Load pending payment tracked by resourceId from the idempotency record
        if (keyRecord.getResourceId() == null) {
            throw new IllegalStateException("No pending payment resource linked to idempotency key: " + request.idempotencyKey());
        }
        Payment pending = paymentRepository.findById(keyRecord.getResourceId())
                .orElseThrow(() -> new IllegalStateException("Pending payment not found for resourceId: " + keyRecord.getResourceId()));

        if (pending.getStatus() != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("Payment for resource " + keyRecord.getResourceId() + " is not in PROCESSING status: " + pending.getStatus());
        }

        // Execute provider call
        PaymentProvider selectedProvider = providerFactory.getProvider(request.provider());
        return selectedProvider.processPayment(
                new PaymentRequest(pending.getOrderId(), request.provider(), request.idempotencyKey()),
                pending.getAmount()
        );
    }
}
