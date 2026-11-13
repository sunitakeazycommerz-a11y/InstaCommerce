package com.instacommerce.audit.service;

import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.domain.model.AuditEventBuilder;
import com.instacommerce.audit.dto.AuditEventRequest;
import com.instacommerce.audit.dto.AuditEventResponse;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.time.Instant;
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
        Instant createdAt = Instant.now();
        hashChainService.acquireChainLock();

        String previousHash = hashChainService.getLatestHash();
        long seqNum = hashChainService.nextSequenceNumber();
        AuditEvent event = buildChainedEvent(request, seqNum, previousHash, createdAt);

        AuditEvent saved = repository.save(event);
        log.debug("Ingested audit event: id={}, type={}, source={}, seq={}",
                saved.getId(), saved.getEventType(), saved.getSourceService(), saved.getSequenceNumber());
        return toResponse(saved);
    }

    @Transactional
    public void ingestBatch(List<AuditEventRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        Instant createdAt = Instant.now();
        hashChainService.acquireChainLock();

        String previousHash = hashChainService.getLatestHash();
        List<AuditEvent> events = new ArrayList<>(requests.size());

        for (AuditEventRequest request : requests) {
            long seqNum = hashChainService.nextSequenceNumber();
            AuditEvent event = buildChainedEvent(request, seqNum, previousHash, createdAt);
            events.add(event);
            previousHash = event.getEventHash();
        }

        repository.saveAll(events);
        log.info("Batch ingested {} audit events with hash chain", events.size());
    }

    private AuditEvent buildChainedEvent(AuditEventRequest request, long seqNum,
                                         String previousHash, Instant createdAt) {
        AuditEventBuilder builder = AuditEvent.builder()
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
                .createdAt(createdAt)
                .sequenceNumber(seqNum)
                .previousHash(previousHash);

        String eventHash = hashChainService.computeEventHash(builder.build());
        return builder.eventHash(eventHash).build();
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
