package com.umurinan.hikaricp.client;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a slow external pricing service. The point of this class is to be slow
 * on purpose so the tutorial's @Transactional anti-pattern bites quickly under load.
 */
@Component
public class PricingClient {

    private final long delayMs;

    public PricingClient(@Value("${pricing.delay-ms:2000}") long delayMs) {
        this.delayMs = delayMs;
    }

    public BigDecimal calculate(List<String> items) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return BigDecimal.valueOf(items.size() * 9.99);
    }
}
