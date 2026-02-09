package com.instacommerce.catalog.controller;

import com.instacommerce.catalog.dto.request.PricingComputeRequest;
import com.instacommerce.catalog.dto.response.PricingBreakdownResponse;
import com.instacommerce.catalog.service.PricingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pricing")
public class PricingController {
    private final PricingService pricingService;

    public PricingController(PricingService pricingService) {
        this.pricingService = pricingService;
    }

    @PostMapping("/compute")
    public PricingBreakdownResponse compute(@Valid @RequestBody PricingComputeRequest request) {
        return pricingService.compute(request);
    }
}
