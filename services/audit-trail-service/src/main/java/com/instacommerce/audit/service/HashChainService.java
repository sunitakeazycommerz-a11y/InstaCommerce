package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditEvent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HashChainService {

    private static final Logger log = LoggerFactory.getLogger(HashChainService.class);
    static final String GENESIS_SEED = "INSTACOMMERCE_AUDIT_GENESIS";

    private final JdbcTemplate jdbcTemplate;

    public HashChainService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long nextSequenceNumber() {
        return jdbcTemplate.queryForObject("SELECT nextval('audit_chain_seq')", Long.class);
    }

    public String getLatestHash() {
        var results = jdbcTemplate.queryForList(
                "SELECT event_hash FROM audit_events WHERE sequence_number IS NOT NULL " +
                "ORDER BY sequence_number DESC LIMIT 1", String.class);
        if (results.isEmpty()) {
            return sha256(GENESIS_SEED);
        }
        return results.get(0);
    }

    public String computeEventHash(AuditEvent event) {
        String canonical = String.join("|",
                String.valueOf(event.getSequenceNumber()),
                nullSafe(event.getEventType()),
                nullSafe(event.getSourceService()),
                nullSafe(event.getAction()),
                event.getActorId() != null ? event.getActorId().toString() : "",
                nullSafe(event.getResourceType()),
                nullSafe(event.getResourceId()),
                nullSafe(event.getCorrelationId()),
                event.getCreatedAt() != null ? event.getCreatedAt().toString() : "",
                nullSafe(event.getPreviousHash()));
        return sha256(canonical);
    }

    static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
