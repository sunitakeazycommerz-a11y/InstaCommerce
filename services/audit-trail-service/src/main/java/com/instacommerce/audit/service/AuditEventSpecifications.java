package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditSearchCriteria;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class AuditEventSpecifications {

    private AuditEventSpecifications() {
    }

    public static Specification<AuditEvent> fromCriteria(AuditSearchCriteria criteria) {
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
