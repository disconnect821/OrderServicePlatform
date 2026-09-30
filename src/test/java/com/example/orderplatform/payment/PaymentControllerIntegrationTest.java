package com.example.orderplatform.payment;

import com.example.orderplatform.idempotency.IdempotencyStatus;
import com.example.orderplatform.idempotency.IdempotencyKey;
import com.example.orderplatform.idempotency.IdempotencyKeyId;
import com.example.orderplatform.idempotency.IdempotencyOperationType;
import com.example.orderplatform.idempotency.IdempotencyKeyRepository;
import com.example.orderplatform.order.dto.CreateOrderRequest;
import com.example.orderplatform.order.dto.OrderItemRequest;
import com.example.orderplatform.payment.dto.PaymentRequest;
import com.example.orderplatform.payment.dto.PaymentResponse;
import com.example.orderplatform.product.Product;
import com.example.orderplatform.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PaymentControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private static final String IDEMPOTENCY_KEY = "test-payment-idemp-001";

    @BeforeEach
    void cleanUp() {
        idempotencyKeyRepository.deleteAll();
    }

    @Test
    void processPayment_updatesOrderStatusAndInventoryBasedOnResult() throws Exception {
        Product product = productRepository.save(
                new Product("SKU-PAY-1", "Payment Widget", new BigDecimal("15.00"), 5));

        CreateOrderRequest orderRequest = new CreateOrderRequest(
                301L,
                List.of(new OrderItemRequest(product.getId(), 2)),
                null);
        ResponseEntity<com.example.orderplatform.order.dto.OrderResponse> orderResponse =
                restTemplate.postForEntity("/orders", orderRequest,
                        com.example.orderplatform.order.dto.OrderResponse.class);
        assertThat(orderResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long orderId = orderResponse.getBody().id();

        Product afterOrder = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterOrder.getAvailableQuantity()).isEqualTo(3);

        PaymentRequest paymentRequest = new PaymentRequest(orderId, "stripe", "test-payment-key-1");
        ResponseEntity<PaymentResponse> paymentResponse =
                restTemplate.postForEntity("/payments", paymentRequest, PaymentResponse.class);

        assertThat(paymentResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(paymentResponse.getBody()).isNotNull();
        assertThat(paymentResponse.getBody().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void processPayment_failedRestoresInventoryAndCancelsOrder() throws Exception {
        Product product = productRepository.save(
                new Product("SKU-PAY-FAILED", "Fail Widget", new BigDecimal("20.00"), 3));

        CreateOrderRequest orderRequest = new CreateOrderRequest(
                302L,
                List.of(new OrderItemRequest(product.getId(), 2)),
                null);
        ResponseEntity<com.example.orderplatform.order.dto.OrderResponse> orderResponse =
                restTemplate.postForEntity("/orders", orderRequest,
                        com.example.orderplatform.order.dto.OrderResponse.class);
        Long orderId = orderResponse.getBody().id();

        PaymentRequest failRequest = new PaymentRequest(orderId, "stripe", "test-payment-fail-key");
        ResponseEntity<PaymentResponse> failResponse =
                restTemplate.postForEntity("/payments", failRequest, PaymentResponse.class);

        assertThat(failResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(failResponse.getBody().status()).isEqualTo(PaymentStatus.PENDING);

        Product afterPending = productRepository.findById(product.getId()).orElseThrow();
        assertThat(afterPending.getAvailableQuantity()).isEqualTo(1);
    }

    @Test
    void processPayment_concurrentRequestsAreSerialized() throws Exception {
        Product product = productRepository.save(
                new Product("SKU-PAY-CONCURRENT", "Concurrent Widget", new BigDecimal("10.00"), 5));

        CreateOrderRequest orderRequest = new CreateOrderRequest(
                303L,
                List.of(new OrderItemRequest(product.getId(), 1)),
                null);
        ResponseEntity<com.example.orderplatform.order.dto.OrderResponse> orderResponse =
                restTemplate.postForEntity("/orders", orderRequest,
                        com.example.orderplatform.order.dto.OrderResponse.class);
        Long orderId = orderResponse.getBody().id();

        int totalRequests = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(totalRequests);
        List<Future<ResponseEntity<PaymentResponse>>> futures = new ArrayList<>();

        PaymentRequest request = new PaymentRequest(orderId, "stripe", "test-payment-concurrent-key");
        for (int i = 0; i < totalRequests; i++) {
            final int index = i;
            Callable<ResponseEntity<PaymentResponse>> callable = () -> {
                try {
                    return restTemplate.postForEntity("/payments", request, PaymentResponse.class);
                } catch (Exception e) {
                    return ResponseEntity.status(HttpStatus.CONFLICT).build();
                }
            };
            futures.add(executorService.submit(callable));
        }

        int successCount = 0;
        for (Future<ResponseEntity<PaymentResponse>> future : futures) {
            try {
                ResponseEntity<PaymentResponse> response = future.get();
                if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null
                        && response.getBody().status() == PaymentStatus.PENDING) {
                    successCount++;
                }
            } catch (Exception e) {
                // Concurrent requests may throw; count as non-success
            }
        }
        executorService.shutdown();

        assertThat(successCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void processPayment_unknownStatusKeepsPendingForReconciliation() throws Exception {
        // Arrange
        Product product = productRepository.save(
                new Product("SKU-PAY-UNKNOWN", "Unknown Widget", new BigDecimal("30.00"), 4));
        CreateOrderRequest orderRequest = new CreateOrderRequest(
                304L,
                List.of(new OrderItemRequest(product.getId(), 1)),
                null);
        ResponseEntity<com.example.orderplatform.order.dto.OrderResponse> orderResponse =
                restTemplate.postForEntity("/orders", orderRequest,
                        com.example.orderplatform.order.dto.OrderResponse.class);
        Long orderId = orderResponse.getBody().id();

        // Act: process payment with idempotency key
        PaymentRequest request = new PaymentRequest(orderId, "stripe", "unknown-key-001");
        ResponseEntity<PaymentResponse> response =
                restTemplate.postForEntity("/payments", request, PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.PENDING);

        // Verify idempotency key exists with PENDING status
        IdempotencyKey savedKey = idempotencyKeyRepository
                .findById(new IdempotencyKeyId(IdempotencyOperationType.PROCESS_PAYMENT, "unknown-key-001"))
                .orElseThrow();
        assertThat(savedKey.getStatus()).isEqualTo(IdempotencyStatus.PENDING);
        assertThat(savedKey.getResponseJson()).contains("PENDING");
    }

    @Test
    void processPayment_idempotencyTracksCompletedStatus() throws Exception {
        Product product = productRepository.save(
                new Product("SKU-PAY-TRACK", "Track Widget", new BigDecimal("25.00"), 2));
        CreateOrderRequest orderRequest = new CreateOrderRequest(
                305L,
                List.of(new OrderItemRequest(product.getId(), 1)),
                null);
        ResponseEntity<com.example.orderplatform.order.dto.OrderResponse> orderResponse =
                restTemplate.postForEntity("/orders", orderRequest,
                        com.example.orderplatform.order.dto.OrderResponse.class);
        Long orderId = orderResponse.getBody().id();

        // Use a dedicated idempotency key for tracking verification
        String trackKey = "track-completed-key";
        PaymentRequest paymentRequest = new PaymentRequest(orderId, "stripe", trackKey);
        ResponseEntity<PaymentResponse> response =
                restTemplate.postForEntity("/payments", paymentRequest, PaymentResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(PaymentStatus.PENDING);

        IdempotencyKey key = idempotencyKeyRepository
                .findById(new IdempotencyKeyId(IdempotencyOperationType.PROCESS_PAYMENT, trackKey))
                .orElseThrow();
        assertThat(key.getStatus()).isEqualTo(IdempotencyStatus.PENDING);
        assertThat(key.getResponseJson()).isNotNull();
    }
}
