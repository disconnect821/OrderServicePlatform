package com.example.orderplatform.payment;

import com.example.orderplatform.common.ResourceNotFoundException;
import com.example.orderplatform.order.Order;
import com.example.orderplatform.order.OrderItem;
import com.example.orderplatform.order.OrderRepository;
import com.example.orderplatform.idempotency.IdempotencyKey;
import com.example.orderplatform.idempotency.IdempotencyStatus;
import com.example.orderplatform.idempotency.IdempotencyOperationType;
import com.example.orderplatform.idempotency.IdempotencyKeyRepository;
import com.example.orderplatform.order.OrderStatus;
import com.example.orderplatform.payment.provider.PaymentProviderFactory;
import com.example.orderplatform.product.Product;
import com.example.orderplatform.product.ProductRepository;
import com.example.orderplatform.payment.dto.PaymentRequest;
import com.example.orderplatform.payment.dto.PaymentResponse;
import com.example.orderplatform.payment.provider.PaymentProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class PaymentService {

    private final PaymentProviderFactory providerFactory;
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public PaymentService(com.example.orderplatform.payment.provider.PaymentProviderFactory providerFactory,
                          OrderRepository orderRepository,
                          ProductRepository productRepository,
                          PaymentRepository paymentRepository,
                          IdempotencyKeyRepository idempotencyKeyRepository) {
        this.providerFactory = providerFactory;
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.paymentRepository = paymentRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional
    public PaymentResponse processPayment(PaymentRequest request) {

        //  Lock order row first (serializes concurrent attempts for same order)
        Order order = orderRepository.findByIdWithLock(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order " + request.orderId() + " not found"));

        //  Atomically insert idempotency key to serialize concurrent requests for same key+operation
        String requestHash = computeRequestHash(request);
        int inserted = idempotencyKeyRepository.insertIfAbsent(
                request.idempotencyKey(), IdempotencyOperationType.PROCESS_PAYMENT.name(), order.getUserId(), requestHash);

        //  Lock idempotency row — only one concurrent request per (operation, key) can proceed
        IdempotencyKey keyRecord = idempotencyKeyRepository
                .findByOperationAndKeyWithLock(IdempotencyOperationType.PROCESS_PAYMENT, request.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency key missing after insert"));

        if (!keyRecord.getUserId().equals(order.getUserId())) {
            throw new IllegalArgumentException("Idempotency key belongs to a different user");
        }
        if (!keyRecord.getRequestHash().equals(requestHash)) {
            throw new IllegalArgumentException("Request payload does not match original idempotency key request");
        }

        // Return cached final response if already completed (not PENDING)
        if (keyRecord.getStatus() != null && keyRecord.getStatus() != IdempotencyStatus.PENDING) {
            if (keyRecord.getResponseJson() != null) {
                return parseCachedResponse(keyRecord.getResponseJson());
            }
        }


        // Check if order is already CONFIRMED with existing completed payment
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            Optional<Payment> existing = paymentRepository.findByOrderId(order.getId());
            if (existing.isPresent()) {
                Payment p = existing.get();
                // Update idempotency key with completed result so retries return it
                keyRecord.setStatus(IdempotencyStatus.COMPLETED);
                keyRecord.setResponseJson(p.getStatus() + "|" + p.getResultMessage() + "|" + p.getAmount());
                idempotencyKeyRepository.save(keyRecord);
                return new PaymentResponse(p.getStatus(), "Payment already completed for order: " + order.getId(), p.getAmount());
            }
        }

        // Reject payment for cancelled orders
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return new PaymentResponse(PaymentStatus.CANCELLED, "Order was already cancelled: " + request.orderId(), order.getTotalAmount());
        }

        // Find or create PENDING payment for this order
        Optional<Payment> existingPending = paymentRepository.findByOrderId(order.getId());
        Payment payment;
        if (existingPending.isPresent() && existingPending.get().getStatus() == PaymentStatus.PENDING) {
            payment = existingPending.get();
        } else {
            BigDecimal amount = order.getTotalAmount();
            payment = new Payment(
                    request.orderId(),
                    amount,
                    PaymentStatus.PENDING,
                    request.provider(),
                    "Payment not initiated"
            );
        }
        paymentRepository.save(payment);

        //  Save idempotency with PENDING result
        String json = payment.getStatus() + "|" + payment.getResultMessage() + "|" + payment.getAmount();
        keyRecord.setResponseJson(json);
        keyRecord.setStatus(IdempotencyStatus.PENDING);
        keyRecord.setResourceId(payment.getId());
        idempotencyKeyRepository.save(keyRecord);

        order.setStatus(OrderStatus.PAYMENT_PENDING);
        orderRepository.save(order);

        return new PaymentResponse(payment.getStatus(), payment.getResultMessage(), payment.getAmount());
    }

    // Separate transaction: calls payment provider without holding order/product locks
    @Transactional
    public PaymentResponse executeProviderPayment(PaymentRequest request) {
        // Validate idempotency key presence
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required for payment execution requests");
        }

        String requestHash = computeRequestHash(request);

        // Load order (for user validation and status check) without pessimistic locks
        Order order = orderRepository.findByIdWithLock(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + request.orderId()));

        // Atomically insert idempotency key if missing (protect against concurrent executions)
        int inserted = idempotencyKeyRepository.insertIfAbsent(
                request.idempotencyKey(), IdempotencyOperationType.PROCESS_PAYMENT.name(), order.getUserId(), requestHash);

        // Lock idempotency row — serializes concurrent execution attempts for the same key
        IdempotencyKey keyRecord = idempotencyKeyRepository
                .findByOperationAndKeyWithLock(IdempotencyOperationType.PROCESS_PAYMENT, request.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency key missing after insert"));

        // Validate user and payload match (same logic as processPayment)
        if (!keyRecord.getUserId().equals(order.getUserId())) {
            throw new IllegalArgumentException("Idempotency key belongs to a different user");
        }
        if (!keyRecord.getRequestHash().equals(requestHash)) {
            throw new IllegalArgumentException("Request payload does not match original idempotency key request");
        }

        // Final responses (COMPLETED or FAILED) are cached; PENDING and UNKNOWN allow retry/reconciliation.
        if (keyRecord.getStatus() != null && (keyRecord.getStatus() == IdempotencyStatus.COMPLETED || keyRecord.getStatus() == IdempotencyStatus.FAILED)) {
            if (keyRecord.getResponseJson() != null) {
                return parseCachedResponse(keyRecord.getResponseJson());
            }
        }

        // Reject execution for cancelled orders
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return new PaymentResponse(PaymentStatus.CANCELLED, "Order was already cancelled: " + request.orderId(), order.getTotalAmount());
        }

        // Load existing PENDING payment created by processPayment
        Payment pending = paymentRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("No pending payment found for order: " + request.orderId()));

        // Phase 3 (separate transaction): external provider call — NO DB locks held during network call
        PaymentResponse providerResponse = getPaymentResponse(request, pending);

        // Phase 4 (new transaction): save provider result and update tracking safely
        return saveExecutionResult(request, providerResponse, pending, order, keyRecord);
    }

    // Separate transactional save: applies provider result without holding provider call locks
    @Transactional
    private PaymentResponse saveExecutionResult(PaymentRequest request, PaymentResponse providerResponse, Payment pending, Order order, IdempotencyKey keyRecord) {
        // Update payment
        pending.setStatus(providerResponse.status());
        pending.setResultMessage(providerResponse.message());
        pending.setUpdatedAt(java.time.Instant.now());
        paymentRepository.save(pending);

        // Apply order updates
        applyStatusDrivenUpdates(order, providerResponse.status());

        // Track idempotency: COMPLETED / FAILED / PENDING (for UNKNOWN — allows retry/reconciliation safely)
        String json = providerResponse.status() + "|" + providerResponse.message() + "|" + providerResponse.amount();
        keyRecord.setResponseJson(json);
        if (providerResponse.status() == PaymentStatus.SUCCESS) {
            keyRecord.setStatus(IdempotencyStatus.COMPLETED);
        } else if (providerResponse.status() == PaymentStatus.FAILED) {
            keyRecord.setStatus(IdempotencyStatus.FAILED);
        } else if (providerResponse.status() == PaymentStatus.UNKNOWN) {
            // Timeout/network failure: actual payment may have succeeded; keep PENDING for safe retry/reconciliation.
            keyRecord.setStatus(IdempotencyStatus.PENDING);
        } else {
            keyRecord.setStatus(IdempotencyStatus.PENDING);
        }
        idempotencyKeyRepository.save(keyRecord);
        return providerResponse;
    }

    private PaymentResponse getPaymentResponse(PaymentRequest request, Payment pending) {
        if (pending.getStatus() != PaymentStatus.PENDING) {
            throw new IllegalStateException("Payment for order " + request.orderId() + " is not in PENDING status: " + pending.getStatus());
        }

        BigDecimal amount = pending.getAmount();
        PaymentProvider selectedProvider = providerFactory.getProvider(request.provider());

        // Call provider
        PaymentResponse providerResponse = selectedProvider.processPayment(request, amount);
        return providerResponse;
    }

    private String computeRequestHash(PaymentRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = request.orderId() + "|" + request.provider();
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute request hash", e);
        }
    }

    private PaymentResponse parseCachedResponse(String json) {
        String[] parts = json.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalStateException("Invalid cached response format");
        }
        PaymentStatus status = PaymentStatus.valueOf(parts[0]);
        String message = parts[1];
        BigDecimal amount = new BigDecimal(parts[2]);
        return new PaymentResponse(status, message, amount);
    }

    private void applyStatusDrivenUpdates(Order order, PaymentStatus status) {
        switch (status) {
            case SUCCESS -> {
                order.setStatus(OrderStatus.CONFIRMED);
                // Inventory already decremented during order creation; no reversal needed
            }
            case PENDING -> {
                order.setStatus(OrderStatus.PAYMENT_PENDING);
                // Inventory reserved (decremented by order); maintain pending state
            }
            case UNKNOWN -> {
                // Timeout/network failure: actual payment may have succeeded.
                // Keep order in PENDING state; idempotency key tracks the attempt.
                // A retry via idempotency key will reconcile or call provider again safely.
                order.setStatus(OrderStatus.PAYMENT_PENDING);
            }
            case FAILED, CANCELLED -> {
                order.setStatus(OrderStatus.CANCELLED);
                // Restore inventory for each product in the order
                for (OrderItem item : order.getItems()) {
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
