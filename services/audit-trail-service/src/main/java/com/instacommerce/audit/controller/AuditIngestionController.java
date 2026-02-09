package com.instacommerce.audit.controller;

import com.instacommerce.audit.dto.AuditEventRequest;
import com.instacommerce.audit.dto.AuditEventResponse;
import com.instacommerce.audit.service.AuditIngestionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/audit")
public class AuditIngestionController {

    private final AuditIngestionService ingestionService;

    public AuditIngestionController(AuditIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @PostMapping("/events")
    public ResponseEntity<AuditEventResponse> ingest(@Valid @RequestBody AuditEventRequest request) {
        AuditEventResponse response = ingestionService.ingest(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/events/batch")
    public ResponseEntity<Void> ingestBatch(@Valid @Size(max = 1000) @RequestBody List<AuditEventRequest> requests) {
        ingestionService.ingestBatch(requests);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
