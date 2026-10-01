package com.example.orderplatform.payment;

import com.example.orderplatform.common.ResourceNotFoundException;
import com.example.orderplatform.idempotency.IdempotencyKey;
import com.example.orderplatform.idempotency.IdempotencyStatus;
import com.example.orderplatform.idempotency.IdempotencyOperationType;
import com.example.orderplatform.idempotency.IdempotencyKeyRepository;
import com.example.orderplatform.order.Order;
import com.example.orderplatform.order.OrderRepository;
import com.example.orderplatform.order.OrderStatus;
import com.example.orderplatform.payment.dto.PaymentExecutionContext;
import com.example.orderplatform.payment.dto.PaymentRequest;
import com.example.orderplatform.payment.dto.PaymentResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class PaymentPreparationService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentPreparationService(OrderRepository orderRepository,
                                     PaymentRepository paymentRepository,
                                     IdempotencyKeyRepository idempotencyKeyRepository) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional
    public PaymentExecutionContext prepare(PaymentRequest request) {
        Order order = orderRepository.findByIdWithLock(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + request.orderId()));

        String requestHash = computeRequestHash(request);
        int inserted = idempotencyKeyRepository.insertIfAbsent(
                request.idempotencyKey(), IdempotencyOperationType.PROCESS_PAYMENT.name(), order.getUserId(), requestHash);

        IdempotencyKey keyRecord = idempotencyKeyRepository
                .findByOperationAndKeyWithLock(IdempotencyOperationType.PROCESS_PAYMENT, request.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("Idempotency key missing after insert"));

        if (!keyRecord.getUserId().equals(order.getUserId())) {
            throw new IllegalArgumentException("Idempotency key belongs to a different user");
        }
        if (!keyRecord.getRequestHash().equals(requestHash)) {
            throw new IllegalArgumentException("Request payload does not match original idempotency key request");
        }

        if (keyRecord.getStatus() != null && keyRecord.getStatus() != IdempotencyStatus.PENDING) {
            if (keyRecord.getResponseJson() != null) {
                //return cached response without provider call
                PaymentResponse cached = parseCachedResponse(keyRecord.getResponseJson());
                throw new IdempotencyFinalizedException(cached);
            }
        }

        if (order.getStatus() == OrderStatus.CONFIRMED) {
            Optional<Payment> existing = paymentRepository.findByOrderId(order.getId());
            if (existing.isPresent()) {
                Payment p = existing.get();
                keyRecord.setStatus(IdempotencyStatus.COMPLETED);
                keyRecord.setResponseJson(p.getStatus() + "|" + p.getResultMessage() + "|" + p.getAmount());
                idempotencyKeyRepository.save(keyRecord);
                throw new IdempotencyFinalizedException(new PaymentResponse(p.getStatus(), p.getResultMessage(), p.getAmount()));
            }
        }

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Order was already cancelled: " + request.orderId());
        }

        Optional<Payment> existingPending = paymentRepository.findByOrderId(order.getId());
        Payment payment;
        if (existingPending.isPresent()) {
            Payment existing = existingPending.get();
            if (existing.getStatus() == PaymentStatus.PROCESSING) {
                // Already in progress; do not claim again
                throw new IllegalStateException("Payment processing already in progress for order: " + request.orderId());
            }
            if (existing.getStatus() == PaymentStatus.PENDING || existing.getStatus() == PaymentStatus.UNKNOWN) {
                payment = existing;
            } else {
                BigDecimal amount = order.getTotalAmount();
                payment = new Payment(
                        request.orderId(),
                        amount,
                        PaymentStatus.PENDING,
                        request.provider(),
                        "Payment not initiated"
                );
                paymentRepository.save(payment);
            }
        } else {
            BigDecimal amount = order.getTotalAmount();
            payment = new Payment(
                    request.orderId(),
                    amount,
                    PaymentStatus.PENDING,
                    request.provider(),
                    "Payment not initiated"
            );
            paymentRepository.save(payment);
        }

        // Provider consistency: reject if existing payment has different provider
        if (existingPending.isPresent() && !existingPending.get().getProviderName().equals(request.provider())) {
            throw new IllegalArgumentException("Existing payment provider does not match request provider: expected "
                    + existingPending.get().getProviderName() + " but got " + request.provider());
        }

        // Transition to PROCESSING before committing
        payment.setStatus(PaymentStatus.PROCESSING);
        paymentRepository.save(payment);

        // Transition to PROCESSING before committing; store structured JSON for future reconciliation
        String json;
        try {
            json = objectMapper.writeValueAsString(new PaymentResponse(PaymentStatus.PROCESSING, "Payment not initiated", payment.getAmount()));
        } catch (Exception ex) {
            json = PaymentStatus.PROCESSING.name() + "|Payment not initiated|" + payment.getAmount();
        }
        keyRecord.setResponseJson(json);
        keyRecord.setStatus(IdempotencyStatus.PENDING);
        keyRecord.setResourceId(payment.getId());
        idempotencyKeyRepository.save(keyRecord);

        order.setStatus(OrderStatus.PAYMENT_PENDING);
        orderRepository.save(order);

        return new PaymentExecutionContext(
                payment.getId(),
                order.getId(),
                request.idempotencyKey(),
                request.provider(),
                payment.getAmount()
        );
    }

    private PaymentResponse parseCachedResponse(String json) {
        try {
            return objectMapper.readValue(json, PaymentResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid cached response JSON format");
        }
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
}
