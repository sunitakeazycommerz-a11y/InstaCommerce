package com.instacommerce.featureflag.controller;

import com.instacommerce.featureflag.domain.model.FlagAuditLog;
import com.instacommerce.featureflag.dto.request.AddOverrideRequest;
import com.instacommerce.featureflag.dto.request.CreateFlagRequest;
import com.instacommerce.featureflag.dto.request.UpdateFlagRequest;
import com.instacommerce.featureflag.dto.response.FlagResponse;
import com.instacommerce.featureflag.service.FlagManagementService;
import com.instacommerce.featureflag.service.FlagOverrideService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/flags")
public class AdminFlagController {

    private final FlagManagementService flagManagementService;
    private final FlagOverrideService flagOverrideService;

    public AdminFlagController(FlagManagementService flagManagementService,
                               FlagOverrideService flagOverrideService) {
        this.flagManagementService = flagManagementService;
        this.flagOverrideService = flagOverrideService;
    }

    @PostMapping
    public ResponseEntity<FlagResponse> createFlag(@Valid @RequestBody CreateFlagRequest request,
                                                   Principal principal) {
        String changedBy = principal != null ? principal.getName() : "system";
        FlagResponse response = flagManagementService.createFlag(request, changedBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{key}")
    public ResponseEntity<FlagResponse> updateFlag(@PathVariable String key,
                                                   @Valid @RequestBody UpdateFlagRequest request,
                                                   Principal principal) {
        String changedBy = principal != null ? principal.getName() : "system";
        FlagResponse response = flagManagementService.updateFlag(key, request, changedBy);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{key}")
    public ResponseEntity<FlagResponse> getFlag(@PathVariable String key) {
        return ResponseEntity.ok(flagManagementService.getFlag(key));
    }

    @GetMapping
    public ResponseEntity<List<FlagResponse>> getAllFlags() {
        return ResponseEntity.ok(flagManagementService.getAllFlags());
    }

    @PostMapping("/{key}/enable")
    public ResponseEntity<FlagResponse> enableFlag(@PathVariable String key, Principal principal) {
        String changedBy = principal != null ? principal.getName() : "system";
        return ResponseEntity.ok(flagManagementService.enableFlag(key, changedBy));
    }

    @PostMapping("/{key}/disable")
    public ResponseEntity<FlagResponse> disableFlag(@PathVariable String key, Principal principal) {
        String changedBy = principal != null ? principal.getName() : "system";
        return ResponseEntity.ok(flagManagementService.disableFlag(key, changedBy));
    }

    @PostMapping("/{key}/rollout")
    public ResponseEntity<FlagResponse> setRollout(@PathVariable String key,
                                                   @RequestBody Map<String, Integer> body,
                                                   Principal principal) {
        String changedBy = principal != null ? principal.getName() : "system";
        int percentage = body.getOrDefault("percentage", 0);
        return ResponseEntity.ok(flagManagementService.setRolloutPercentage(key, percentage, changedBy));
    }

    @GetMapping("/{key}/audit")
    public ResponseEntity<List<FlagAuditLog>> getAuditLog(@PathVariable String key) {
        return ResponseEntity.ok(flagManagementService.getAuditLog(key));
    }

    @PostMapping("/{key}/overrides")
    public ResponseEntity<Void> addOverride(@PathVariable String key,
                                            @Valid @RequestBody AddOverrideRequest request,
                                            Principal principal) {
        String changedBy = principal != null ? principal.getName() : "system";
        flagOverrideService.addOverride(key, request, changedBy);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{key}/overrides")
    public ResponseEntity<Void> removeOverride(@PathVariable String key,
                                               @RequestParam UUID userId,
                                               Principal principal) {
        String changedBy = principal != null ? principal.getName() : "system";
        flagOverrideService.removeOverride(key, userId, changedBy);
        return ResponseEntity.noContent().build();
    }
}
