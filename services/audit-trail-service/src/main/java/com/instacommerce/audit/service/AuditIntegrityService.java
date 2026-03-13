package com.instacommerce.audit.service;

import com.instacommerce.audit.controller.AuditIntegrityController.IntegrityReport;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityService.class);
    private final AuditEventRepository repository;
    private final HashChainService hashChainService;

    public AuditIntegrityService(AuditEventRepository repository, HashChainService hashChainService) {
        this.repository = repository;
        this.hashChainService = hashChainService;
    }

    @Transactional(readOnly = true)
    public IntegrityReport verifyChain(Instant from, Instant to, int batchSize) {
        long eventsVerified = 0;
        long chainBreaks = 0;
        String firstBreakSequence = null;

        int page = 0;
        while (true) {
            var pageRequest = PageRequest.of(page, batchSize, Sort.by("sequenceNumber"));
            var events = repository.findChainedEvents(from, to, pageRequest);
            if (events.isEmpty()) break;

            for (AuditEvent event : events) {
                String recomputed = hashChainService.computeEventHash(event);
                if (!recomputed.equals(event.getEventHash())) {
                    chainBreaks++;
                    if (firstBreakSequence == null) {
                        firstBreakSequence = String.valueOf(event.getSequenceNumber());
                    }
                    log.warn("integrity.chain_break seq={} expected={} actual={}",
                            event.getSequenceNumber(), recomputed, event.getEventHash());
                }
                eventsVerified++;
            }
            page++;
        }

        return new IntegrityReport(eventsVerified, chainBreaks, chainBreaks == 0, firstBreakSequence, Instant.now());
    }
}
