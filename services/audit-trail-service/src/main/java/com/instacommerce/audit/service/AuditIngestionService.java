package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.dto.AuditEventRequest;
import com.instacommerce.audit.dto.AuditEventResponse;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditIngestionService {

    private static final Logger log = LoggerFactory.getLogger(AuditIngestionService.class);
    private final AuditEventRepository repository;
    private final HashChainService hashChainService;

    public AuditIngestionService(AuditEventRepository repository, HashChainService hashChainService) {
        this.repository = repository;
        this.hashChainService = hashChainService;
    }

    @Transactional
    public AuditEventResponse ingest(AuditEventRequest request) {
        String previousHash = hashChainService.getLatestHash();
        long seqNum = hashChainService.nextSequenceNumber();

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
                .sequenceNumber(seqNum)
                .previousHash(previousHash)
                .build();

        String eventHash = hashChainService.computeEventHash(event);
        // Rebuild with the computed hash
        event = AuditEvent.builder()
                .eventType(event.getEventType())
                .sourceService(event.getSourceService())
                .actorId(event.getActorId())
                .actorType(event.getActorType())
                .resourceType(event.getResourceType())
                .resourceId(event.getResourceId())
                .action(event.getAction())
                .details(event.getDetails())
                .ipAddress(event.getIpAddress())
                .userAgent(event.getUserAgent())
                .correlationId(event.getCorrelationId())
                .createdAt(event.getCreatedAt())
                .sequenceNumber(seqNum)
                .previousHash(previousHash)
                .eventHash(eventHash)
                .build();

        AuditEvent saved = repository.save(event);
        log.debug("Ingested audit event: id={}, type={}, source={}, seq={}",
                saved.getId(), saved.getEventType(), saved.getSourceService(), saved.getSequenceNumber());
        return toResponse(saved);
    }

    @Transactional
    public void ingestBatch(List<AuditEventRequest> requests) {
        String previousHash = hashChainService.getLatestHash();
        List<AuditEvent> events = new ArrayList<>();

        for (AuditEventRequest request : requests) {
            long seqNum = hashChainService.nextSequenceNumber();

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
                    .sequenceNumber(seqNum)
                    .previousHash(previousHash)
                    .build();

            String eventHash = hashChainService.computeEventHash(event);
            event = AuditEvent.builder()
                    .eventType(event.getEventType())
                    .sourceService(event.getSourceService())
                    .actorId(event.getActorId())
                    .actorType(event.getActorType())
                    .resourceType(event.getResourceType())
                    .resourceId(event.getResourceId())
                    .action(event.getAction())
                    .details(event.getDetails())
                    .ipAddress(event.getIpAddress())
                    .userAgent(event.getUserAgent())
                    .correlationId(event.getCorrelationId())
                    .createdAt(event.getCreatedAt())
                    .sequenceNumber(seqNum)
                    .previousHash(previousHash)
                    .eventHash(eventHash)
                    .build();

            events.add(event);
            previousHash = eventHash;
        }

        repository.saveAll(events);
        log.info("Batch ingested {} audit events with hash chain", events.size());
    }

    static AuditEventResponse toResponse(AuditEvent event) {
        return new AuditEventResponse(
                event.getId(), event.getEventType(), event.getSourceService(),
                event.getActorId(), event.getActorType(), event.getResourceType(),
                event.getResourceId(), event.getAction(), event.getDetails(),
                event.getIpAddress(), event.getUserAgent(), event.getCorrelationId(),
                event.getSequenceNumber(), event.getEventHash(), event.getPreviousHash(),
                event.getCreatedAt());
    }
}
