package com.instacommerce.wallet.controller;

import com.instacommerce.wallet.dto.request.RedeemReferralRequest;
import com.instacommerce.wallet.dto.response.ReferralCodeResponse;
import com.instacommerce.wallet.security.AuthenticatedUserResolver;
import com.instacommerce.wallet.service.ReferralService;
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
@RequestMapping({"/referral", "/api/v1/referral"})
public class ReferralController {
    private final ReferralService referralService;

    public ReferralController(ReferralService referralService) {
        this.referralService = referralService;
    }

    @GetMapping("/code")
    public ResponseEntity<ReferralCodeResponse> getCode(
            Authentication auth,
            @RequestHeader(value = AuthenticatedUserResolver.USER_ID_HEADER, required = false) String userIdHeader) {
        UUID userId = AuthenticatedUserResolver.resolveUserId(auth, userIdHeader);
        return ResponseEntity.ok(referralService.getOrGenerateCode(userId));
    }

    @PostMapping("/redeem")
    public ResponseEntity<Void> redeemReferral(
            Authentication auth,
            @RequestHeader(value = AuthenticatedUserResolver.USER_ID_HEADER, required = false) String userIdHeader,
            @Valid @RequestBody RedeemReferralRequest request) {
        UUID userId = AuthenticatedUserResolver.resolveUserId(auth, userIdHeader);
        referralService.redeemReferral(request.code(), userId);
        return ResponseEntity.ok().build();
    }
}
