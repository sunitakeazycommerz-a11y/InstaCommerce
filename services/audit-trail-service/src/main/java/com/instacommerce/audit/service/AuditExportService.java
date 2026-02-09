package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditSearchCriteria;
import com.instacommerce.audit.repository.AuditEventRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public AuditExportService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public void exportCsv(AuditSearchCriteria criteria, HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=audit_export.csv");

        try (PrintWriter writer = response.getWriter()) {
            writer.println("id,event_type,source_service,actor_id,actor_type,resource_type," +
                    "resource_id,action,ip_address,user_agent,correlation_id,created_at");

            Specification<AuditEvent> spec = buildSpecification(criteria);
            int page = 0;
            Page<AuditEvent> batch;

            do {
                PageRequest pageRequest = PageRequest.of(page, EXPORT_BATCH_SIZE,
                        Sort.by(Sort.Direction.ASC, "createdAt"));
                batch = repository.findAll(spec, pageRequest);

                for (AuditEvent event : batch.getContent()) {
                    writer.println(toCsvRow(event));
                }

                writer.flush();
                page++;
            } while (batch.hasNext());

            log.info("Exported {} audit events to CSV", batch.getTotalElements());
        }
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
                escapeCsv(event.getCreatedAt() != null ? ISO_FORMATTER.format(event.getCreatedAt()) : "")
        );
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
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
