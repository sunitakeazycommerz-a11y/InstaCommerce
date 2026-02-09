package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditEventRequest;
import com.instacommerce.audit.dto.AuditEventResponse;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditIngestionService {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestionService.class);
    private final AuditEventRepository repository;

    public AuditIngestionService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AuditEventResponse ingest(AuditEventRequest request) {
        AuditEvent event = AuditEvent.builder()
                .eventType(request.eventType())
                .sourceService(request.sourceService())
                .actorId(request.actorId())
                .actorType(request.actorType())
                .resourceType(request.resourceType())
                .resourceId(request.resourceId())
                .action(request.action())
                .details(request.details())
                .ipAddress(request.ipAddress())
                .userAgent(request.userAgent())
                .correlationId(request.correlationId())
                .build();

        AuditEvent saved = repository.save(event);
        log.debug("Ingested audit event: id={}, type={}, source={}", saved.getId(), saved.getEventType(), saved.getSourceService());
        return toResponse(saved);
    }

    @Transactional
    public void ingestBatch(List<AuditEventRequest> requests) {
        List<AuditEvent> events = requests.stream()
                .map(request -> AuditEvent.builder()
                        .eventType(request.eventType())
                        .sourceService(request.sourceService())
                        .actorId(request.actorId())
                        .actorType(request.actorType())
                        .resourceType(request.resourceType())
                        .resourceId(request.resourceId())
                        .action(request.action())
                        .details(request.details())
                        .ipAddress(request.ipAddress())
                        .userAgent(request.userAgent())
                        .correlationId(request.correlationId())
                        .build())
                .toList();

        repository.saveAll(events);
        log.info("Batch ingested {} audit events", events.size());
    }

    static AuditEventResponse toResponse(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(),
                event.getEventType(),
                event.getSourceService(),
                event.getActorId(),
                event.getActorType(),
                event.getResourceType(),
                event.getResourceId(),
                event.getAction(),
                event.getDetails(),
                event.getIpAddress(),
                event.getUserAgent(),
                event.getCorrelationId(),
                event.getCreatedAt()
        );
    }
}
