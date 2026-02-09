package com.instacommerce.wallet.controller;

import com.instacommerce.wallet.dto.request.RedeemPointsRequest;
import com.instacommerce.wallet.dto.response.LoyaltyResponse;
import com.instacommerce.wallet.service.LoyaltyService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/loyalty")
public class LoyaltyController {
    private final LoyaltyService loyaltyService;

    public LoyaltyController(LoyaltyService loyaltyService) {
        this.loyaltyService = loyaltyService;
    }

    @GetMapping("/points")
    public ResponseEntity<LoyaltyResponse> getPoints(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(loyaltyService.getBalance(userId));
    }

    @PostMapping("/redeem")
    public ResponseEntity<LoyaltyResponse> redeemPoints(Authentication auth,
                                                         @Valid @RequestBody RedeemPointsRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(loyaltyService.redeemPoints(userId, request.points()));
    }
}
