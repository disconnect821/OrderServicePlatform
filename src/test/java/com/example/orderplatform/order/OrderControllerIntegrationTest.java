package com.example.orderplatform.order;

import com.example.orderplatform.order.dto.CreateOrderRequest;
import com.example.orderplatform.order.dto.OrderItemRequest;
import com.example.orderplatform.order.dto.OrderResponse;
import com.example.orderplatform.product.Product;
import com.example.orderplatform.product.ProductRepository;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

// This test spins up a REAL Postgres in Docker via Testcontainers.
// A mocked/in-memory database would not catch bugs like the unique
// constraint on sku, the CHECK (available_quantity >= 0), or the
// JOIN FETCH query actually working against real SQL.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderControllerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProductRepository productRepository;

    @Test
    void createOrder_reducesStockAndPersistsOrder() {
        Product product = productRepository.save(
                new Product("SKU-WIDGET-1", "Widget", new BigDecimal("9.99"), 5));

        CreateOrderRequest request = new CreateOrderRequest(
                101L,
                List.of(new OrderItemRequest(product.getId(), 2)));

        ResponseEntity<OrderResponse> response =
                restTemplate.postForEntity("/orders", request, OrderResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(OrderStatus.CREATED);
        assertThat(response.getBody().totalAmount()).isEqualByComparingTo("19.98");

        Product updated = productRepository.findById(product.getId()).orElseThrow();
        assertThat(updated.getAvailableQuantity()).isEqualTo(3);
    }

    @Test
    void createOrder_rejectsWhenStockInsufficient() {
        Product product = productRepository.save(
                new Product("SKU-WIDGET-2", "Widget", new BigDecimal("9.99"), 1));

        CreateOrderRequest request = new CreateOrderRequest(
                101L,
                List.of(new OrderItemRequest(product.getId(), 5)));

        ResponseEntity<String> response =
                restTemplate.postForEntity("/orders", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getOrder_returns404WhenMissing() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/orders/999999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createOrder_withPessimisticLocking_allowsOnlyConcurrentStockUnits() throws Exception {
        // Arrange: Create a product with only 2 units available
        Product product = productRepository.save(
                new Product("SKU-WIDGET-CONCURRENT", "Widget Concurrent", new BigDecimal("10.00"), 2));

        int totalRequests = 5;
        int orderQuantity = 1; // Each order requests 1 unit
        ExecutorService executorService = Executors.newFixedThreadPool(totalRequests);
        List<Future<ResponseEntity<OrderResponse>>> futures = new ArrayList<>();

        // Create request for each concurrent order
        CreateOrderRequest request = new CreateOrderRequest(
                201L,
                List.of(new OrderItemRequest(product.getId(), orderQuantity)));

        // Submit all concurrent requests
        for (int i = 0; i < totalRequests; i++) {
            final int requestIndex = i;
            Callable<ResponseEntity<OrderResponse>> callable = () -> {
                try {
                    return restTemplate.postForEntity("/orders", request, OrderResponse.class);
                } catch (Exception e) {
                    // Return a response indicating the failure
                    return ResponseEntity.status(HttpStatus.CONFLICT).build();
                }
            };
            futures.add(executorService.submit(callable));
        }

        // Collect results
        List<ResponseEntity<OrderResponse>> results = new ArrayList<>();
        int successfulOrders = 0;
        for (Future<ResponseEntity<OrderResponse>> future : futures) {
            ResponseEntity<OrderResponse> response = future.get();
            results.add(response);
            if (response.getStatusCode() == HttpStatus.CREATED) {
                successfulOrders++;
            }
        }

        // Clean up executor
        executorService.shutdown();

        // Assert: With pessimistic locking and 2 units available,
        // only 2 orders should succeed (one for each unit)
        assertThat(successfulOrders).isEqualTo(2);

        // Assert: Verify that exactly 2 orders were created
        long createdOrdersCount = results.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CREATED)
                .count();
        assertThat(createdOrdersCount).isEqualTo(2);

        // Assert: Verify that the remaining 3 requests failed due to insufficient stock
        long failedRequestsCount = results.stream()
                .filter(response -> response.getStatusCode() == HttpStatus.CONFLICT)
                .count();
        assertThat(failedRequestsCount).isEqualTo(3);

        // Assert: Verify that stock was properly decremented to 0 (all units sold)
        Product updatedProduct = productRepository.findById(product.getId())
                .orElseThrow();
        assertThat(updatedProduct.getAvailableQuantity()).isEqualTo(0);
    }

}
