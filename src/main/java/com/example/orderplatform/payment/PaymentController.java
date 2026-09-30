package com.example.orderplatform.payment;

import com.example.orderplatform.payment.dto.PaymentRequest;
import com.example.orderplatform.payment.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> processPayment(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.processPayment(request);
        return ResponseEntity.ok(response);
    }

    //Temp external call
    @PostMapping("/execute")
    public ResponseEntity<PaymentResponse> executePayment(@Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.executeProviderPayment(request);
        return ResponseEntity.ok(response);
    }
}
