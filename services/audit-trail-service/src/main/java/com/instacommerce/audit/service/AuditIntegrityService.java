package com.instacommerce.audit.service;

import com.instacommerce.audit.controller.AuditIntegrityController.IntegrityReport;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.repository.AuditArchiveManifestRepository;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditIntegrityService {

    private static final String VERIFIED_ARCHIVE_STATUS = "VERIFIED";
    private static final Logger log = LoggerFactory.getLogger(AuditIntegrityService.class);
    private final AuditEventRepository repository;
    private final HashChainService hashChainService;
    private final AuditArchiveManifestRepository manifestRepository;

    public AuditIntegrityService(AuditEventRepository repository, HashChainService hashChainService) {
        this(repository, hashChainService, null);
    }

    @Autowired
    public AuditIntegrityService(AuditEventRepository repository, HashChainService hashChainService,
                                 AuditArchiveManifestRepository manifestRepository) {
        this.repository = repository;
        this.hashChainService = hashChainService;
        this.manifestRepository = manifestRepository;
    }

    @Transactional(readOnly = true)
    public IntegrityReport verifyChain(Instant from, Instant to, int batchSize) {
        long eventsVerified = 0;
        long chainBreaks = 0;
        String firstBreakSequence = null;
        String expectedPreviousHash = null;

        int page = 0;
        while (true) {
            var pageRequest = PageRequest.of(page, batchSize, Sort.by("sequenceNumber"));
            var events = repository.findChainedEvents(from, to, pageRequest);
            if (events.isEmpty()) {
                break;
            }

            if (eventsVerified == 0) {
                expectedPreviousHash = resolveInitialExpectedPreviousHash(from, events.get(0));
            }

            for (AuditEvent event : events) {
                String recomputed = hashChainService.computeEventHash(event);
                boolean hashValid = recomputed.equals(event.getEventHash());
                boolean linkValid = expectedPreviousHash != null
                        ? expectedPreviousHash.equals(event.getPreviousHash())
                        : event.getPreviousHash() != null && !event.getPreviousHash().isBlank();
                if (!hashValid || !linkValid) {
                    chainBreaks++;
                    if (firstBreakSequence == null) {
                        firstBreakSequence = String.valueOf(event.getSequenceNumber());
                    }
                    log.warn("integrity.chain_break seq={} expectedPreviousHash={} actualPreviousHash={} " +
                                    "expectedEventHash={} actualEventHash={}",
                            event.getSequenceNumber(), expectedPreviousHash, event.getPreviousHash(),
                            recomputed, event.getEventHash());
                }
                expectedPreviousHash = event.getEventHash();
                eventsVerified++;
            }
            page++;
        }

        return new IntegrityReport(eventsVerified, chainBreaks, chainBreaks == 0, firstBreakSequence, Instant.now());
    }

    private String resolveInitialExpectedPreviousHash(Instant from, AuditEvent firstEvent) {
        if (from == null) {
            if (firstEvent.getSequenceNumber() != null && firstEvent.getSequenceNumber() > 1L
                    && hasVerifiedArchive()) {
                return null;
            }
            return hashChainService.genesisHash();
        }
        return repository.findFirstBySequenceNumberLessThanOrderBySequenceNumberDesc(firstEvent.getSequenceNumber())
                .map(AuditEvent::getEventHash)
                .orElseGet(() -> Long.valueOf(1L).equals(firstEvent.getSequenceNumber())
                        ? hashChainService.genesisHash()
                        : null);
    }

    private boolean hasVerifiedArchive() {
        return manifestRepository != null && manifestRepository.existsByVerificationStatus(VERIFIED_ARCHIVE_STATUS);
    }
}
