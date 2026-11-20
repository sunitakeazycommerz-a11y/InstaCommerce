package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditEventResponse;
import com.instacommerce.audit.dto.AuditSearchCriteria;
import com.instacommerce.audit.repository.AuditEventRepository;
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
        if (criteria == null) {
            throw new IllegalArgumentException("criteria must not be null");
        }
        PageRequest pageRequest = PageRequest.of(criteria.page(), criteria.size(),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<AuditEvent> spec = AuditEventSpecifications.fromCriteria(criteria);
        return repository.findAll(spec, pageRequest).map(AuditIngestionService::toResponse);
    }
}
