package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.WalletDebitResult;
import com.instacommerce.checkout.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Component
public class WalletActivityImpl implements WalletActivity {

    private static final Logger log = LoggerFactory.getLogger(WalletActivityImpl.class);

    private final RestTemplate restTemplate;

    public WalletActivityImpl(@Qualifier("walletRestTemplate") RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    @CircuitBreaker(name = "walletService", fallbackMethod = "debitWalletFallback")
    public WalletDebitResult debitWallet(String userId, long amountCents, String referenceId, String description) {
        log.info("Debiting wallet userId={} amountCents={} referenceId={}", userId, amountCents, referenceId);
        try {
            WalletDebitResult result = restTemplate.postForObject(
                "/wallet/debit",
                Map.of(
                    "userId", userId,
                    "amountCents", amountCents,
                    "referenceType", "ORDER",
                    "referenceId", referenceId,
                    "description", description != null ? description : "Checkout split-tender debit"
                ),
                WalletDebitResult.class
            );
            if (result == null) {
                return new WalletDebitResult(referenceId, false, amountCents, "Empty response from wallet service");
            }
            return result;
        } catch (HttpClientErrorException ex) {
            log.warn("Wallet debit rejected userId={} referenceId={} status={}: {}",
                userId, referenceId, ex.getStatusCode(), ex.getMessage());
            return new WalletDebitResult(referenceId, false, amountCents, ex.getMessage());
        }
    }

    @Override
    @CircuitBreaker(name = "walletService", fallbackMethod = "refundWalletFallback")
    public void refundWallet(String userId, long amountCents, String referenceId, String description) {
        log.info("Refunding wallet userId={} amountCents={} referenceId={}", userId, amountCents, referenceId);
        restTemplate.postForObject(
            "/wallet/credit",
            Map.of(
                "userId", userId,
                "amountCents", amountCents,
                "referenceType", "REFUND",
                "referenceId", referenceId,
                "description", description != null ? description : "Checkout split-tender compensation"
            ),
            Void.class
        );
    }

    @SuppressWarnings("unused")
    private WalletDebitResult debitWalletFallback(String userId, long amountCents, String referenceId,
                                                  String description, Exception e) {
        log.warn("Circuit breaker fallback for walletService debitWallet userId={} referenceId={}: {}",
            userId, referenceId, e.getMessage());
        throw new ServiceUnavailableException("walletService",
            "Wallet service unavailable for debit referenceId=" + referenceId, e);
    }

    @SuppressWarnings("unused")
    private void refundWalletFallback(String userId, long amountCents, String referenceId,
                                      String description, Exception e) {
        log.warn("Circuit breaker fallback for walletService refundWallet userId={} referenceId={}: {}",
            userId, referenceId, e.getMessage());
        throw new ServiceUnavailableException("walletService",
            "Wallet service unavailable for refund referenceId=" + referenceId, e);
    }
}
