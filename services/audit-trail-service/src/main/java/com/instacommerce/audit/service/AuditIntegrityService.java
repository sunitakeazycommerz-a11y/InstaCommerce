package com.instacommerce.audit.service;

import com.instacommerce.audit.controller.AuditIntegrityController.IntegrityReport;
import com.instacommerce.audit.domain.model.AuditArchiveManifest;
import com.instacommerce.audit.domain.model.AuditEvent;
import com.instacommerce.audit.repository.AuditArchiveManifestRepository;
import com.instacommerce.audit.repository.AuditEventRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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
        boolean initialAnchorValid = true;

        int page = 0;
        while (true) {
            var pageRequest = PageRequest.of(page, batchSize, Sort.by("sequenceNumber"));
            var events = repository.findChainedEvents(from, to, pageRequest);
            if (events.isEmpty()) {
                break;
            }

            if (eventsVerified == 0) {
                InitialAnchor anchor = resolveInitialAnchor(from, events.get(0));
                initialAnchorValid = anchor.valid();
                expectedPreviousHash = anchor.expectedPreviousHash();
            }

            for (AuditEvent event : events) {
                String recomputed = event.getHashVersion() <= 1
                        ? hashChainService.computeLegacyV1EventHash(event)
                        : hashChainService.computeEventHash(event);
                boolean hashValid = recomputed.equals(event.getEventHash());
                boolean anchorOk = eventsVerified > 0 || initialAnchorValid;
                boolean linkValid = anchorOk && (expectedPreviousHash != null
                        ? expectedPreviousHash.equals(event.getPreviousHash())
                        : event.getPreviousHash() != null && !event.getPreviousHash().isBlank());
                if (!hashValid || !linkValid) {
                    chainBreaks++;
                    if (firstBreakSequence == null) {
                        firstBreakSequence = String.valueOf(event.getSequenceNumber());
                    }
                    log.warn("integrity.chain_break seq={} expectedPreviousHash={} actualPreviousHash={} " +
                                    "expectedEventHash={} actualEventHash={} anchorValid={}",
                            event.getSequenceNumber(), expectedPreviousHash, event.getPreviousHash(),
                            recomputed, event.getEventHash(), anchorOk);
                }
                expectedPreviousHash = event.getEventHash();
                eventsVerified++;
            }
            page++;
        }

        return new IntegrityReport(eventsVerified, chainBreaks, chainBreaks == 0, firstBreakSequence, Instant.now());
    }

    private InitialAnchor resolveInitialAnchor(Instant from, AuditEvent firstEvent) {
        Long firstSeq = firstEvent.getSequenceNumber();
        if (from == null) {
            if (firstSeq != null && firstSeq > 1L) {
                return resolveArchiveAnchor(firstEvent);
            }
            return new InitialAnchor(true, hashChainService.genesisHash());
        }
        return repository.findFirstBySequenceNumberLessThanOrderBySequenceNumberDesc(firstSeq)
                .map(pred -> new InitialAnchor(true, pred.getEventHash()))
                .orElseGet(() -> Long.valueOf(1L).equals(firstSeq)
                        ? new InitialAnchor(true, hashChainService.genesisHash())
                        : new InitialAnchor(true, null));
    }

    private InitialAnchor resolveArchiveAnchor(AuditEvent firstEvent) {
        if (manifestRepository == null) {
            return new InitialAnchor(false, null);
        }
        List<AuditArchiveManifest> verified =
                manifestRepository.findByVerificationStatusOrderByDateRangeStartAsc(VERIFIED_ARCHIVE_STATUS);
        if (verified == null || verified.isEmpty()) {
            return new InitialAnchor(false, null);
        }

        Instant firstCreatedAt = firstEvent.getCreatedAt();
        List<AuditArchiveManifest> coveringNonEmpty = verified.stream()
                .filter(m -> m.getRowCount() != null && m.getRowCount() > 0)
                .filter(m -> firstCreatedAt == null || m.getDateRangeEnd() == null
                        || !m.getDateRangeEnd().isAfter(firstCreatedAt))
                .sorted(Comparator
                        .comparing(AuditArchiveManifest::getFirstSequence,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AuditArchiveManifest::getDateRangeStart,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        if (coveringNonEmpty.isEmpty()) {
            return new InitialAnchor(false, null);
        }

        long firstSeq = firstEvent.getSequenceNumber();
        List<AuditArchiveManifest> sequenceBounded = coveringNonEmpty.stream()
                .filter(m -> m.getFirstSequence() != null && m.getLastSequence() != null)
                .toList();

        if (!sequenceBounded.isEmpty()) {
            if (sequenceBounded.size() != coveringNonEmpty.size()) {
                return new InitialAnchor(false, null);
            }
            AuditArchiveManifest earliest = sequenceBounded.get(0);
            if (!Long.valueOf(1L).equals(earliest.getFirstSequence())) {
                return new InitialAnchor(false, null);
            }
            AuditArchiveManifest prev = null;
            for (AuditArchiveManifest current : sequenceBounded) {
                if (current.getFirstSequence() > current.getLastSequence()
                        || current.getLastSequence() >= firstSeq) {
                    return new InitialAnchor(false, null);
                }
                if (prev != null) {
                    if (current.getFirstSequence() <= prev.getLastSequence()) {
                        return new InitialAnchor(false, null);
                    }
                    boolean seqContiguous = current.getFirstSequence().equals(prev.getLastSequence() + 1L);
                    boolean dateContiguous = prev.getDateRangeEnd() != null
                            && current.getDateRangeStart() != null
                            && !current.getDateRangeStart().isAfter(prev.getDateRangeEnd());
                    if (!seqContiguous && !dateContiguous) {
                        return new InitialAnchor(false, null);
                    }
                }
                prev = current;
            }
            AuditArchiveManifest latest = sequenceBounded.get(sequenceBounded.size() - 1);
            if (latest.getLastEventHash() != null && !latest.getLastEventHash().isBlank()) {
                return new InitialAnchor(true, latest.getLastEventHash());
            }
            if (Long.valueOf(firstSeq - 1L).equals(latest.getLastSequence())) {
                return new InitialAnchor(true, null);
            }
            return new InitialAnchor(false, null);
        }

        // Legacy manifests without sequence bounds: validate covering non-empty date range.
        AuditArchiveManifest latestLegacy = coveringNonEmpty.get(coveringNonEmpty.size() - 1);
        boolean coversPriorWindow = latestLegacy.getDateRangeEnd() != null
                && (firstCreatedAt == null || !latestLegacy.getDateRangeEnd().isAfter(firstCreatedAt));
        return new InitialAnchor(coversPriorWindow, null);
    }

    private record InitialAnchor(boolean valid, String expectedPreviousHash) {}
}
