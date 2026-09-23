package com.example.orderplatform.order;

import com.example.orderplatform.common.InsufficientStockException;
import com.example.orderplatform.common.ResourceNotFoundException;
import com.example.orderplatform.order.dto.CreateOrderRequest;
import com.example.orderplatform.order.dto.OrderItemRequest;
import com.example.orderplatform.order.dto.OrderResponse;
import com.example.orderplatform.product.Product;
import com.example.orderplatform.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    // Everything below runs in one transaction: if any item fails
    // (missing product, insufficient stock), the whole order - including
    // any stock already decremented in this loop - rolls back. Without
    // @Transactional here you could end up with partially-decremented
    // stock and no order to show for it.
    @Transactional
    public OrderResponse createOrder(CreateOrderRequest request) {
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
        return OrderResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long id) {
        Order order = orderRepository.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " not found"));
        return OrderResponse.from(order);
    }
}
