package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HashChainService {

    static final String GENESIS_SEED = "INSTACOMMERCE_AUDIT_GENESIS";
    private static final DateTimeFormatter PARTITION_SUFFIX_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy_MM").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbcTemplate;

    public HashChainService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String partitionBucket(Instant timestamp) {
        Instant effective = timestamp != null ? timestamp : Instant.now();
        return "audit_events_" + PARTITION_SUFFIX_FORMATTER.format(effective);
    }

    public static final String GLOBAL_CHAIN_LOCK = "audit_events_global_chain";

    public long advisoryLockKey(String partitionBucket) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("audit_chain:" + partitionBucket).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(hash).getLong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public void acquireChainLock() {
        acquirePartitionLock(GLOBAL_CHAIN_LOCK);
    }

    public void acquirePartitionLock(String partitionBucket) {
        long lockKey = advisoryLockKey(partitionBucket);
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, lockKey);
    }

    public String genesisHash() {
        return sha256(GENESIS_SEED);
    }

    public long nextSequenceNumber() {
        return jdbcTemplate.queryForObject("SELECT nextval('audit_chain_seq')", Long.class);
    }

    public String getLatestHash() {
        var results = jdbcTemplate.queryForList(
                "SELECT event_hash FROM audit_events WHERE sequence_number IS NOT NULL " +
                "ORDER BY sequence_number DESC LIMIT 1", String.class);
        if (results.isEmpty()) {
            return genesisHash();
        }
        return results.get(0);
    }

    public String computeEventHash(AuditEvent event) {
        String createdAtStr = event.getCreatedAt() != null
                ? event.getCreatedAt().truncatedTo(ChronoUnit.MICROS).toString()
                : "";
        String canonical = String.join("|",
                String.valueOf(event.getSequenceNumber()),
                event.getId() != null ? event.getId().toString() : "",
                nullSafe(event.getEventType()),
                nullSafe(event.getSourceService()),
                nullSafe(event.getAction()),
                event.getActorId() != null ? event.getActorId().toString() : "",
                nullSafe(event.getActorType()),
                nullSafe(event.getResourceType()),
                nullSafe(event.getResourceId()),
                canonicalizeDetails(event.getDetails()),
                nullSafe(event.getIpAddress()),
                nullSafe(event.getUserAgent()),
                nullSafe(event.getCorrelationId()),
                createdAtStr,
                nullSafe(event.getPreviousHash()));
        return sha256(canonical);
    }

    private static String canonicalizeDetails(Map<String, Object> details) {
        if (details == null) {
            return "";
        }
        return canonicalizeValue(details);
    }

    private static String canonicalizeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(e -> String.valueOf(e.getKey())))
                    .map(e -> String.valueOf(e.getKey()) + "=" + canonicalizeValue(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                    .map(HashChainService::canonicalizeValue)
                    .collect(Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
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
