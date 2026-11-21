package com.instacommerce.wallet.controller;

import com.instacommerce.wallet.dto.request.RedeemPointsRequest;
import com.instacommerce.wallet.dto.response.LoyaltyResponse;
import com.instacommerce.wallet.security.AuthenticatedUserResolver;
import com.instacommerce.wallet.service.LoyaltyService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/loyalty", "/api/v1/loyalty"})
public class LoyaltyController {
    private final LoyaltyService loyaltyService;

    public LoyaltyController(LoyaltyService loyaltyService) {
        this.loyaltyService = loyaltyService;
    }

    @GetMapping("/points")
    public ResponseEntity<LoyaltyResponse> getPoints(
            Authentication auth,
            @RequestHeader(value = AuthenticatedUserResolver.USER_ID_HEADER, required = false) String userIdHeader) {
        UUID userId = AuthenticatedUserResolver.resolveUserId(auth, userIdHeader);
        return ResponseEntity.ok(loyaltyService.getBalance(userId));
    }

    public ResponseEntity<LoyaltyResponse> getPoints(Authentication auth) {
        return getPoints(auth, null);
    }

    @PostMapping("/redeem")
    public ResponseEntity<LoyaltyResponse> redeemPoints(
            Authentication auth,
            @RequestHeader(value = AuthenticatedUserResolver.USER_ID_HEADER, required = false) String userIdHeader,
            @Valid @RequestBody RedeemPointsRequest request) {
        UUID userId = AuthenticatedUserResolver.resolveUserId(auth, userIdHeader);
        return ResponseEntity.ok(loyaltyService.redeemPoints(userId, request.points(), request.orderId()));
    }

    public ResponseEntity<LoyaltyResponse> redeemPoints(Authentication auth, RedeemPointsRequest request) {
        return redeemPoints(auth, null, request);
    }
}
