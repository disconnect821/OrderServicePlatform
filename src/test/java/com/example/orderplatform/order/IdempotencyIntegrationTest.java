package com.example.orderplatform.order;

import com.example.orderplatform.idempotency.IdempotencyKeyRepository;
import com.example.orderplatform.order.dto.CreateOrderRequest;
import com.example.orderplatform.product.Product;
import com.example.orderplatform.product.ProductRepository;
import com.example.orderplatform.order.dto.OrderItemRequest;
import com.example.orderplatform.order.dto.OrderResponse;
import com.example.orderplatform.product.Product;
import com.example.orderplatform.product.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public class IdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private static final String IDEMPOTENCY_KEY = "test-key-uuid-1234";

    @BeforeEach
    void cleanUp() {
        idempotencyKeyRepository.deleteAll();
    }

    @Test
    void firstRequestWithIdempotencyKeyCreatesOrder() {
        Product product = productRepository.save(
                new Product("SKU-IDEMP-1", "Idempotent Widget", new BigDecimal("15.00"), 10));

        CreateOrderRequest request = new CreateOrderRequest(
                1001L,
                List.of(new OrderItemRequest(product.getId(), 2)),
                IDEMPOTENCY_KEY
        );


        ResponseEntity<OrderResponse> response = restTemplate.postForEntity("/orders", request, OrderResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().totalAmount()).isEqualByComparingTo("30.00");
    }

    @Test
    void duplicateRequestReturnsSameResponseWithoutCreatingNewOrder() {
        Product product = productRepository.save(
                new Product("SKU-IDEMP-2", "Idempotent Widget", new BigDecimal("20.00"), 5));

        CreateOrderRequest request = new CreateOrderRequest(
                1002L,
                List.of(new OrderItemRequest(product.getId(), 1)),
                IDEMPOTENCY_KEY
        );

        ResponseEntity<OrderResponse> first = restTemplate.postForEntity("/orders", request, OrderResponse.class);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        Long firstOrderId = first.getBody().id();

        ResponseEntity<OrderResponse> duplicate = restTemplate.postForEntity("/orders", request, OrderResponse.class);
        assertThat(duplicate.getStatusCode().value()).isEqualTo(201);
        assertThat(duplicate.getBody().id()).isEqualTo(firstOrderId);
        assertThat(duplicate.getBody().totalAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void differentPayloadWithSameKeyThrowsException() {
        Product product = productRepository.save(
                new Product("SKU-IDEMP-3", "Idempotent Widget", new BigDecimal("10.00"), 10));

        CreateOrderRequest firstRequest = new CreateOrderRequest(
                1003L,
                List.of(new OrderItemRequest(product.getId(), 1)),
                IDEMPOTENCY_KEY
        );
        restTemplate.postForEntity("/orders", firstRequest, OrderResponse.class);

        CreateOrderRequest differentPayload = new CreateOrderRequest(
                1003L,
                List.of(new OrderItemRequest(product.getId(), 5)), // different quantity = different payload
                IDEMPOTENCY_KEY
        );
        ResponseEntity<String> errorResponse = restTemplate.postForEntity("/orders", differentPayload, String.class);
        assertThat(errorResponse.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void differentUserWithSameKeyThrowsException() {
        Product product = productRepository.save(
                new Product("SKU-IDEMP-4", "Idempotent Widget", new BigDecimal("25.00"), 10));

        CreateOrderRequest firstRequest = new CreateOrderRequest(
                1004L,
                List.of(new OrderItemRequest(product.getId(), 1)),
                IDEMPOTENCY_KEY
        );
        restTemplate.postForEntity("/orders", firstRequest, OrderResponse.class);

        CreateOrderRequest differentUser = new CreateOrderRequest(
                9999L, // different user
                List.of(new OrderItemRequest(product.getId(), 1)),
                IDEMPOTENCY_KEY
        );
        ResponseEntity<String> errorResponse = restTemplate.postForEntity("/orders", differentUser, String.class);
        assertThat(errorResponse.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void concurrentDuplicateRequestsWithSameKeyOnlyCreatesOneOrder() throws Exception {
        Product product = productRepository.save(
                new Product("SKU-CONCURRENT-IDEMP", "Concurrent Widget", new BigDecimal("12.00"), 10));

        String concurrentKey = "concurrent-idemp-key-001";
        CreateOrderRequest request = new CreateOrderRequest(
                2000L,
                List.of(new OrderItemRequest(product.getId(), 2)),
                concurrentKey
        );

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<OrderResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> restTemplate.postForEntity("/orders", request, OrderResponse.class)));
        }

        List<ResponseEntity<OrderResponse>> results = new ArrayList<>();
        for (Future<ResponseEntity<OrderResponse>> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        List<Long> orderIds = results.stream()
                .map(r -> r.getBody() != null ? r.getBody().id() : null)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        assertThat(orderIds).hasSize(1);
    }

    @Test
    void concurrentFirstTimeRequestsWithSameNewIdempotencyKeyCreatesExactlyOneOrder() throws Exception {
        Product product = productRepository.save(
                new Product("SKU-FIRST-DUP", "First Duplicate Widget", new BigDecimal("30.00"), 5));

        String newKey = "first-duplicate-key-new";
        CreateOrderRequest request = new CreateOrderRequest(
                3001L,
                List.of(new OrderItemRequest(product.getId(), 1)),
                newKey
        );

        int threads = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<OrderResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> restTemplate.postForEntity("/orders", request, OrderResponse.class)));
        }

        List<ResponseEntity<OrderResponse>> results = new ArrayList<>();
        for (Future<ResponseEntity<OrderResponse>> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        long successCount = results.stream()
                .filter(r -> r.getStatusCode().value() == 201)
                .count();
        assertThat(successCount).isEqualTo(3);

        Long firstOrderId = results.get(0).getBody().id();
        for (ResponseEntity<OrderResponse> result : results) {
            assertThat(result.getBody().id()).isEqualTo(firstOrderId);
        }
    }

    @Test
    void requestWithoutIdempotencyKeyCreatesOrderNormally() {
        Product product = productRepository.save(
                new Product("SKU-NO-KEY", "No Key Widget", new BigDecimal("50.00"), 3));

        CreateOrderRequest request = new CreateOrderRequest(
                5001L,
                List.of(new OrderItemRequest(product.getId(), 2)),
                null
        );

        ResponseEntity<OrderResponse> response = restTemplate.postForEntity("/orders", request, OrderResponse.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    void concurrentRequestsWithoutIdempotencyKeyCreateMultipleOrders() throws Exception {
        Product product = productRepository.save(
                new Product("SKU-MULTI-NOKEY", "Multiple Orders Widget", new BigDecimal("8.00"), 20));

        int threads = 3;
        CreateOrderRequest noKeyRequest = new CreateOrderRequest(
                6001L,
                List.of(new OrderItemRequest(product.getId(), 1)),
                null
        );

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<ResponseEntity<OrderResponse>>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> restTemplate.postForEntity("/orders", noKeyRequest, OrderResponse.class)));
        }

        List<ResponseEntity<OrderResponse>> results = new ArrayList<>();
        for (Future<ResponseEntity<OrderResponse>> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        executor.shutdown();

        List<Long> orderIds = results.stream()
                .map(r -> r.getBody() != null ? r.getBody().id() : null)
                .filter(Objects::nonNull)
                .toList();
        assertThat(orderIds).hasSize(threads);
    }
}