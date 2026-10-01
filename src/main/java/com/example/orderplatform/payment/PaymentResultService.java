package com.example.orderplatform.payment;

import com.example.orderplatform.idempotency.IdempotencyKey;
import com.example.orderplatform.idempotency.IdempotencyStatus;
import com.example.orderplatform.idempotency.IdempotencyOperationType;
import com.example.orderplatform.idempotency.IdempotencyKeyRepository;
import com.example.orderplatform.order.Order;
import com.example.orderplatform.order.OrderRepository;
import com.example.orderplatform.order.OrderStatus;
import com.example.orderplatform.payment.dto.PaymentExecutionContext;
import com.example.orderplatform.payment.dto.PaymentResponse;
import com.example.orderplatform.product.Product;
import com.example.orderplatform.product.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class PaymentResultService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentResultService(PaymentRepository paymentRepository,
                                OrderRepository orderRepository,
                                IdempotencyKeyRepository idempotencyKeyRepository,
                                ProductRepository productRepository) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public PaymentResponse saveResult(PaymentExecutionContext context, PaymentResponse providerResponse) {
        Payment pending = paymentRepository.findById(context.paymentId())
                .orElseThrow(() -> new IllegalStateException("Pending payment not found: " + context.paymentId()));

        if (pending.getStatus() != PaymentStatus.PROCESSING) {
            throw new IllegalStateException("Expected PROCESSING but found: " + pending.getStatus());
        }

        // Update payment
        pending.setStatus(providerResponse.status());
        pending.setResultMessage(providerResponse.message());
        pending.setUpdatedAt(java.time.Instant.now());
        // If provider returned a provider-specific transaction ID, save it (future reconciliation)
        paymentRepository.save(pending);

        Order order = orderRepository.findById(context.orderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + context.orderId()));

        // Apply order updates based on final status
        applyStatusDrivenUpdates(order, providerResponse.status());

        // Load and update idempotency record
        IdempotencyKey keyRecord = idempotencyKeyRepository
                .findByOperationAndKeyWithLock(IdempotencyOperationType.PROCESS_PAYMENT, context.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency key missing: " + context.idempotencyKey()));

        // Store JSON-serialized response instead of delimiter format
        try {
            String json = objectMapper.writeValueAsString(providerResponse);
            keyRecord.setResponseJson(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize response", e);
        }

        if (providerResponse.status() == PaymentStatus.SUCCESS) {
            keyRecord.setStatus(IdempotencyStatus.COMPLETED);
        } else if (providerResponse.status() == PaymentStatus.FAILED) {
            keyRecord.setStatus(IdempotencyStatus.FAILED);
        } else if (providerResponse.status() == PaymentStatus.UNKNOWN) {
            // UNKNOWN means reconciliation needed; keep PENDING for safe retry/reconciliation
            keyRecord.setStatus(IdempotencyStatus.PENDING);
        } else {
            // PENDING, PROCESSING, CANCELLED fall through safely
            keyRecord.setStatus(IdempotencyStatus.PENDING);
        }
        idempotencyKeyRepository.save(keyRecord);

        return providerResponse;
    }

    private void applyStatusDrivenUpdates(Order order, PaymentStatus status) {
        switch (status) {
            case SUCCESS -> {
                order.setStatus(OrderStatus.CONFIRMED);
            }
            case PROCESSING, PENDING -> {
                order.setStatus(OrderStatus.PAYMENT_PENDING);
            }
            case UNKNOWN -> {
                order.setStatus(OrderStatus.PAYMENT_PENDING);
            }
            case FAILED, CANCELLED -> {
                order.setStatus(OrderStatus.CANCELLED);
                for (com.example.orderplatform.order.OrderItem item : order.getItems()) {
                    Product product = productRepository.findByIdWithLock(item.getProduct().getId()).orElseThrow();
                    int restoredQuantity = product.getAvailableQuantity() + item.getQuantity();
                    product.setAvailableQuantity(restoredQuantity);
                }
            }
            default -> throw new IllegalArgumentException("Unknown payment status: " + status);
        }
        orderRepository.save(order);
    }
}
