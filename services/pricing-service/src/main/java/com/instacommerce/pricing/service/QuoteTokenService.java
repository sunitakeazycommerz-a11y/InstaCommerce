package com.instacommerce.pricing.service;

import com.instacommerce.pricing.config.QuoteTokenProperties;
import com.instacommerce.pricing.domain.PriceQuote;
import com.instacommerce.pricing.dto.response.PriceCalculationResponse;
import com.instacommerce.pricing.repository.PriceQuoteRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuoteTokenService {
    private static final Logger log = LoggerFactory.getLogger(QuoteTokenService.class);

    private final PriceQuoteRepository priceQuoteRepository;
    private final QuoteTokenProperties properties;

    public QuoteTokenService(PriceQuoteRepository priceQuoteRepository,
                             QuoteTokenProperties properties) {
        this.priceQuoteRepository = priceQuoteRepository;
        this.properties = properties;
    }

    @Transactional
    public QuoteResult issueQuote(UUID userId, PriceCalculationResponse response, String couponCode) {
        String contentHash = computeContentHash(
                response.subtotalCents(),
                response.discountCents(),
                response.totalCents(),
                response.items().size());

        PriceQuote quote = new PriceQuote();
        quote.setUserId(userId);
        quote.setContentHash(contentHash);
        quote.setSubtotalCents(response.subtotalCents());
        quote.setDiscountCents(response.discountCents());
        quote.setTotalCents(response.totalCents());
        quote.setCurrency("INR");
        quote.setCouponCode(couponCode);
        quote.setItemCount(response.items().size());
        quote.setExpiresAt(Instant.now().plusSeconds(properties.getTtlSeconds()));

        quote = priceQuoteRepository.save(quote);

        String token = computeHmac(quote.getId(), contentHash);

        log.info("Issued price quote id={} for user={} total={} ttl={}s",
                quote.getId(), userId, response.totalCents(), properties.getTtlSeconds());

        return new QuoteResult(quote.getId(), token);
    }

    public ValidationResult validate(UUID quoteId, String token,
                                     long claimedTotal, long claimedSubtotal, long claimedDiscount) {
        if (!properties.isValidationEnabled()) {
            return ValidationResult.valid();
        }

        PriceQuote quote = priceQuoteRepository.findById(quoteId).orElse(null);
        if (quote == null) {
            return ValidationResult.invalid("NOT_FOUND");
        }

        if (Instant.now().isAfter(quote.getExpiresAt())) {
            return ValidationResult.invalid("EXPIRED");
        }

        String expectedToken = computeHmac(quoteId, quote.getContentHash());
        if (!MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
            return ValidationResult.invalid("INVALID_SIGNATURE");
        }

        if (quote.getTotalCents() != claimedTotal
                || quote.getSubtotalCents() != claimedSubtotal
                || quote.getDiscountCents() != claimedDiscount) {
            return ValidationResult.invalid("PRICE_MISMATCH");
        }

        return ValidationResult.valid();
    }

    private String computeContentHash(long subtotal, long discount, long total, int itemCount) {
        try {
            String data = subtotal + ":" + discount + ":" + total + ":" + itemCount;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String computeHmac(UUID quoteId, String contentHash) {
        try {
            String data = quoteId + ":" + contentHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    properties.getHmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    public record QuoteResult(UUID quoteId, String quoteToken) {
    }

    public record ValidationResult(boolean valid, String reason) {
        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
