package com.instacommerce.audit.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditSearchCriteria;
import com.instacommerce.audit.repository.AuditEventRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditExportService {

    private static final Logger log = LoggerFactory.getLogger(AuditExportService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final int EXPORT_BATCH_SIZE = 500;

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public AuditExportService(AuditEventRepository repository, ObjectMapper objectMapper) {
        this(repository, objectMapper, null);
    }

    @Autowired
    public AuditExportService(AuditEventRepository repository,
                              ObjectMapper objectMapper,
                              EntityManager entityManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public void exportCsv(AuditSearchCriteria criteria, HttpServletResponse response) throws IOException {
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=audit_export.csv");

        Specification<AuditEvent> baseSpec = buildSpecification(criteria);
        long totalExported = 0;

        try (PrintWriter writer = response.getWriter()) {
            writer.println("id,event_type,source_service,actor_id,actor_type,resource_type," +
                    "resource_id,action,ip_address,user_agent,correlation_id,details,created_at");

            Instant lastCreatedAt = null;
            UUID lastId = null;

            while (true) {
                List<AuditEvent> batch = fetchNextBatch(baseSpec, lastCreatedAt, lastId);
                if (batch.isEmpty()) {
                    break;
                }

                for (AuditEvent event : batch) {
                    writer.println(toCsvRow(event));
                }

                AuditEvent last = batch.get(batch.size() - 1);
                lastCreatedAt = last.getCreatedAt();
                lastId = last.getId();
                totalExported += batch.size();

                writer.flush();
                if (entityManager != null) {
                    entityManager.clear();
                }

                if (batch.size() < EXPORT_BATCH_SIZE) {
                    break;
                }
            }

            log.info("Exported {} audit events to CSV", totalExported);
        }
    }

    private List<AuditEvent> fetchNextBatch(Specification<AuditEvent> baseSpec,
                                            Instant lastCreatedAt,
                                            UUID lastId) {
        Specification<AuditEvent> cursorSpec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            Predicate basePredicate = baseSpec.toPredicate(root, query, cb);
            if (basePredicate != null) {
                predicates.add(basePredicate);
            }
            if (lastCreatedAt != null) {
                if (lastId == null) {
                    predicates.add(cb.greaterThan(root.get("createdAt"), lastCreatedAt));
                } else {
                    predicates.add(cb.or(
                            cb.greaterThan(root.get("createdAt"), lastCreatedAt),
                            cb.and(
                                    cb.equal(root.get("createdAt"), lastCreatedAt),
                                    cb.greaterThan(root.get("id"), lastId)
                            )
                    ));
                }
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        PageRequest pageRequest = PageRequest.of(0, EXPORT_BATCH_SIZE,
                Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")));
        Page<AuditEvent> batch = repository.findAll(cursorSpec, pageRequest);
        return batch.getContent();
    }

    private String toCsvRow(AuditEvent event) {
        return String.join(",",
                escapeCsv(str(event.getId())),
                escapeCsv(event.getEventType()),
                escapeCsv(event.getSourceService()),
                escapeCsv(str(event.getActorId())),
                escapeCsv(event.getActorType()),
                escapeCsv(event.getResourceType()),
                escapeCsv(event.getResourceId()),
                escapeCsv(event.getAction()),
                escapeCsv(event.getIpAddress()),
                escapeCsv(event.getUserAgent()),
                escapeCsv(event.getCorrelationId()),
                escapeCsv(serializeDetails(event.getDetails())),
                escapeCsv(event.getCreatedAt() != null ? ISO_FORMATTER.format(event.getCreatedAt()) : "")
        );
    }

    private String serializeDetails(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception ex) {
            log.warn("Failed to serialize audit details for export: {}", ex.getMessage());
            return "";
        }
    }

    static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        String sanitized = value;
        if (!sanitized.isEmpty()) {
            char first = sanitized.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@'
                    || first == '\t' || first == '\r') {
                sanitized = "'" + sanitized;
            }
        }
        if (sanitized.contains(",") || sanitized.contains("\"")
                || sanitized.contains("\n") || sanitized.contains("\r")) {
            return "\"" + sanitized.replace("\"", "\"\"") + "\"";
        }
        return sanitized;
    }

    private static String str(Object obj) {
        return obj != null ? obj.toString() : "";
    }

    private Specification<AuditEvent> buildSpecification(AuditSearchCriteria criteria) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (criteria.actorId() != null) {
                predicates.add(cb.equal(root.get("actorId"), criteria.actorId()));
            }
            if (criteria.resourceType() != null && !criteria.resourceType().isBlank()) {
                predicates.add(cb.equal(root.get("resourceType"), criteria.resourceType()));
            }
            if (criteria.resourceId() != null && !criteria.resourceId().isBlank()) {
                predicates.add(cb.equal(root.get("resourceId"), criteria.resourceId()));
            }
            if (criteria.sourceService() != null && !criteria.sourceService().isBlank()) {
                predicates.add(cb.equal(root.get("sourceService"), criteria.sourceService()));
            }
            if (criteria.eventType() != null && !criteria.eventType().isBlank()) {
                predicates.add(cb.equal(root.get("eventType"), criteria.eventType()));
            }
            if (criteria.correlationId() != null && !criteria.correlationId().isBlank()) {
                predicates.add(cb.equal(root.get("correlationId"), criteria.correlationId()));
            }
            if (criteria.fromDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), criteria.fromDate()));
            }
            if (criteria.toDate() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), criteria.toDate()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
