package com.instacommerce.audit.controller;

import com.instacommerce.audit.service.AuditIntegrityService;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/audit/integrity")
public class AuditIntegrityController {

    private final AuditIntegrityService integrityService;

    public AuditIntegrityController(AuditIntegrityService integrityService) {
        this.integrityService = integrityService;
    }

    @GetMapping("/verify")
    public ResponseEntity<IntegrityReport> verify(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "1000") int batchSize) {
        IntegrityReport report = integrityService.verifyChain(from, to, batchSize);
        return ResponseEntity.ok(report);
    }

    public record IntegrityReport(
        long eventsVerified,
        long chainBreaks,
        boolean intact,
        String firstBreakSequence,
        Instant verifiedAt
    ) {}
}
