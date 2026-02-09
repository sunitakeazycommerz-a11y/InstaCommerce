package com.instacommerce.payment.webhook;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WebhookSignatureVerifier {
    private static final String SIGNATURE_VERSION = "v1";
    private final String webhookSecret;
    private final long timestampToleranceSeconds;

    public WebhookSignatureVerifier(@Value("${stripe.webhook-secret:}") String webhookSecret,
                                    @Value("${stripe.webhook-tolerance-seconds:300}") long timestampToleranceSeconds) {
        this.webhookSecret = webhookSecret;
        this.timestampToleranceSeconds = timestampToleranceSeconds;
    }

    public boolean verify(String payload, String signatureHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        Map<String, List<String>> values = parseSignatureHeader(signatureHeader);
        String timestamp = first(values.get("t"));
        List<String> signatures = values.get(SIGNATURE_VERSION);
        if (timestamp == null || signatures == null || signatures.isEmpty()) {
            return false;
        }
        Long timestampSeconds = parseTimestamp(timestamp);
        if (timestampSeconds == null || isOutsideTolerance(timestampSeconds)) {
            return false;
        }
        String expected = computeSignature(timestamp + "." + payload);
        for (String signature : signatures) {
            if (secureEquals(signature, expected)) {
                return true;
            }
        }
        return false;
    }

    private Map<String, List<String>> parseSignatureHeader(String header) {
        Map<String, List<String>> values = new HashMap<>();
        for (String part : header.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            values.computeIfAbsent(pair[0], key -> new ArrayList<>()).add(pair[1]);
        }
        return values;
    }

    private Long parseTimestamp(String timestamp) {
        try {
            return Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String computeSignature(String signedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return "";
        }
    }

    private boolean isOutsideTolerance(long timestampSeconds) {
        long nowSeconds = System.currentTimeMillis() / 1000;
        long tolerance = Math.max(0L, timestampToleranceSeconds);
        return Math.abs(nowSeconds - timestampSeconds) > tolerance;
    }

    private boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        int diff = expected.length() ^ actual.length();
        for (int i = 0; i < Math.min(expected.length(), actual.length()); i++) {
            diff |= expected.charAt(i) ^ actual.charAt(i);
        }
        return diff == 0;
    }

    private String first(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }
}
