package com.instacommerce.featureflag.controller;

import com.instacommerce.featureflag.dto.request.CreateExperimentRequest;
import com.instacommerce.featureflag.dto.request.UpdateExperimentRequest;
import com.instacommerce.featureflag.dto.response.ExperimentResponse;
import com.instacommerce.featureflag.service.ExperimentManagementService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/experiments")
public class AdminExperimentController {

    private final ExperimentManagementService experimentManagementService;

    public AdminExperimentController(ExperimentManagementService experimentManagementService) {
        this.experimentManagementService = experimentManagementService;
    }

    @PostMapping
    public ResponseEntity<ExperimentResponse> createExperiment(@Valid @RequestBody CreateExperimentRequest request,
                                                               Principal principal) {
        String changedBy = principal != null ? principal.getName() : "system";
        ExperimentResponse response = experimentManagementService.createExperiment(request, changedBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{key}")
    public ResponseEntity<ExperimentResponse> updateExperiment(@PathVariable String key,
                                                               @Valid @RequestBody UpdateExperimentRequest request,
                                                               Principal principal) {
        String changedBy = principal != null ? principal.getName() : "system";
        return ResponseEntity.ok(experimentManagementService.updateExperiment(key, request, changedBy));
    }

    @GetMapping("/{key}")
    public ResponseEntity<ExperimentResponse> getExperiment(@PathVariable String key) {
        return ResponseEntity.ok(experimentManagementService.getExperiment(key));
    }

    @GetMapping
    public ResponseEntity<List<ExperimentResponse>> getAllExperiments() {
        return ResponseEntity.ok(experimentManagementService.getAllExperiments());
    }
}
