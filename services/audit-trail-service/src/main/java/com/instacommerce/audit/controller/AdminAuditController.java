package com.instacommerce.audit.controller;

import com.instacommerce.audit.dto.AuditEventResponse;
import com.instacommerce.audit.dto.AuditSearchCriteria;
import com.instacommerce.audit.service.AuditExportService;
import com.instacommerce.audit.service.AuditQueryService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/audit")
public class AdminAuditController {

    private final AuditQueryService queryService;
    private final AuditExportService exportService;

    public AdminAuditController(AuditQueryService queryService, AuditExportService exportService) {
        this.queryService = queryService;
        this.exportService = exportService;
    }

    @GetMapping("/events")
    public Page<AuditEventResponse> search(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String sourceService,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) Instant fromDate,
            @RequestParam(required = false) Instant toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        AuditSearchCriteria criteria = new AuditSearchCriteria(
                actorId, resourceType, resourceId, sourceService, eventType, correlationId,
                fromDate, toDate, page, size);
        return queryService.query(criteria);
    }

    @GetMapping("/export")
    public void exportCsv(
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String sourceService,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String correlationId,
            @RequestParam(required = false) Instant fromDate,
            @RequestParam(required = false) Instant toDate,
            HttpServletResponse response) throws IOException {

        AuditSearchCriteria criteria = new AuditSearchCriteria(
                actorId, resourceType, resourceId, sourceService, eventType, correlationId,
                fromDate, toDate, 0, Integer.MAX_VALUE);
        exportService.exportCsv(criteria, response);
    }
}
