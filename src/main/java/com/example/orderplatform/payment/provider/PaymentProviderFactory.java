package com.example.orderplatform.payment.provider;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PaymentProviderFactory {
    private final Map<String, PaymentProvider> providers;

    public PaymentProviderFactory(@Qualifier("stripePaymentProvider") PaymentProvider stripeProvider,
                               @Qualifier("payPalPaymentProvider") PaymentProvider payPalProvider) {
        this.providers = Map.of(
                "stripe", stripeProvider,
                "paypal", payPalProvider
        );
    }

    public PaymentProvider getProvider(String name) {
        PaymentProvider provider = providers.get(name != null ? name.toLowerCase() : "stripe");
        if(provider == null) throw new IllegalArgumentException("Invalid Payment provider");
        return provider;
    }
}
