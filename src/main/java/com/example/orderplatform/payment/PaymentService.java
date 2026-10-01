package com.example.orderplatform.payment;


import com.example.orderplatform.payment.dto.PaymentExecutionContext;
import com.example.orderplatform.payment.dto.PaymentExecutionRequest;
import com.example.orderplatform.payment.dto.PaymentRequest;
import com.example.orderplatform.payment.dto.PaymentResponse;
import org.springframework.stereotype.Service;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class PaymentService {

    private final PaymentPreparationService preparationService;
    private final PaymentExecutionService executionService;
    private final PaymentResultService resultService;

    public PaymentService(PaymentPreparationService preparationService,
                          PaymentExecutionService executionService,
                          PaymentResultService resultService) {
        this.preparationService = preparationService;
        this.executionService = executionService;
        this.resultService = resultService;
    }

    public PaymentResponse processPayment(PaymentRequest request) {
        try {
            // Phase 1: transactional preparation (commits independently)
            PaymentExecutionContext context = preparationService.prepare(request);

            // Phase 2: provider execution (non-transactional, no DB locks held)
            PaymentResponse providerResponse = executionService.execute(
                    new PaymentExecutionRequest(context.idempotencyKey(), context.provider()));

            // Phase 3: transactional result persistence
            return resultService.saveResult(context, providerResponse);
        } catch (IdempotencyFinalizedException ex) {
            // Finalized idempotency: return cached response without provider call
            return ex.getCachedResponse();
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
