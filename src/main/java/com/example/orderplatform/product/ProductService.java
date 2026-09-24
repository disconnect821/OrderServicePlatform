package com.example.orderplatform.product;

import com.example.orderplatform.common.ResourceNotFoundException;
import com.example.orderplatform.product.dto.ProductRequest;
import com.example.orderplatform.product.dto.ProductResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        Product product = new Product(
                request.sku(),
                request.name(),
                request.price(),
                request.availableQuantity()
        );
        Product saved = productRepository.save(product);
        return ProductResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product " + id + " not found"));
        return ProductResponse.from(product);
    }

    //Delete All products
}
