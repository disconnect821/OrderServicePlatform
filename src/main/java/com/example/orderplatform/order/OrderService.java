package com.example.orderplatform.order;

import com.example.orderplatform.common.InsufficientStockException;
import com.example.orderplatform.common.ResourceNotFoundException;
import com.example.orderplatform.idempotency.IdempotencyOperationType;
import com.example.orderplatform.order.dto.CreateOrderRequest;
import com.example.orderplatform.order.dto.OrderItemRequest;
import com.example.orderplatform.order.dto.OrderResponse;
import com.example.orderplatform.product.Product;
import com.example.orderplatform.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.orderplatform.idempotency.IdempotencyKey;
import com.example.orderplatform.idempotency.IdempotencyKeyRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository, IdempotencyKeyRepository idempotencyKeyRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    // Everything below runs in one transaction: if any item fails
    // (missing product, insufficient stock), the whole order - including
    // any stock already decremented in this loop - rolls back. Without
    // @Transactional here you could end up with partially-decremented
    // stock and no order to show for it.
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
        String idempotencyKeyValue = request.idempotencyKey();

        String requestHash = null;

        IdempotencyKey keyRecord = null;

        if (idempotencyKeyValue != null && !idempotencyKeyValue.isBlank()) {

            requestHash = computeRequestHash(request);

            //Create the key atomically if its doesn't really exist
            //it makes the repository operation clearer and is useful when debugging concurrent behavior
            int inserted = idempotencyKeyRepository.insertIfAbsent(idempotencyKeyValue, IdempotencyOperationType.CREATE_ORDER.name(), request.userId(), requestHash);

            // Lock idempotency row first (before product locks) to serialize duplicates
            keyRecord = idempotencyKeyRepository
                    .findByOperationAndKeyWithLock(IdempotencyOperationType.CREATE_ORDER, idempotencyKeyValue)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key missing after re-insert"));

            if (keyRecord != null) {
                if (!keyRecord.getUserId().equals(request.userId())) {
                    throw new IllegalArgumentException("Idempotency key belongs to a different user");
                }
                if (!keyRecord.getRequestHash().equals(requestHash)) {
                    throw new IllegalArgumentException("Request payload does not match original idempotency key request");
                }
                if (keyRecord.getResponseJson() != null) {
                    // Return stored response from previous successful request
                    return OrderResponse.fromJson(keyRecord.getResponseJson());
                }
            }
            // At this point:
            // inserted == 1 → this request created the key
            // inserted == 0 → another request created it
            //
            // In either case, responseJson == null means
            // the order still needs to be processed.
        }

        Order order = new Order(request.userId());
        BigDecimal total = BigDecimal.ZERO;

        for (OrderItemRequest itemRequest : request.items()) {
            // Lock the product row to prevent race conditions during stock check and update
            Product product = productRepository.findByIdWithLock(itemRequest.productId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Product " + itemRequest.productId() + " not found"));

            // Check stock with the locked row
            if (product.getAvailableQuantity() < itemRequest.quantity()) {
                throw new InsufficientStockException(
                        "Not enough stock for product " + product.getId());
            }

            // Update stock in the same transaction with exclusive lock
            product.setAvailableQuantity(product.getAvailableQuantity() - itemRequest.quantity());

            OrderItem orderItem = new OrderItem(product, itemRequest.quantity(), product.getPrice());
            order.addItem(orderItem);

            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(itemRequest.quantity())));
        }

        order.setTotalAmount(total);
        Order saved = orderRepository.save(order);
        OrderResponse response = OrderResponse.from(saved);

        if (idempotencyKeyValue != null && !idempotencyKeyValue.isBlank() && keyRecord != null) {
            //Since keyRecord was loaded inside the same @Transactional method, it is a managed JPA entity.
            keyRecord.setResponseJson(response.toJson());
        }

        return response;
    }


    private String computeRequestHash(CreateOrderRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String data = request.userId() + "|" + request.items().toString();
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute request hash", e);
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id) {
        Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " not found"));
        return OrderResponse.from(order);
    }
}
