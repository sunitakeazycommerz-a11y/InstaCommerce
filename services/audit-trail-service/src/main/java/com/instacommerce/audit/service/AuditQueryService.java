package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditEventResponse;
import com.instacommerce.audit.dto.AuditSearchCriteria;
import com.instacommerce.audit.repository.AuditEventRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<AuditEventResponse> query(AuditSearchCriteria criteria) {
        PageRequest pageRequest = PageRequest.of(criteria.page(), criteria.size(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<AuditEvent> spec = buildSpecification(criteria);
        return repository.findAll(spec, pageRequest).map(AuditIngestionService::toResponse);
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
