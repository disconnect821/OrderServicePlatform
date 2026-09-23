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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

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
    void testDockerConnection() {
        var client = DockerClientFactory.instance().client();

        System.out.println(
                "Docker server version: " +
                        client.infoCmd().exec().getServerVersion()
        );
    }
}
