package com.instacommerce.wallet.controller;

import com.instacommerce.wallet.dto.request.RedeemReferralRequest;
import com.instacommerce.wallet.dto.response.ReferralCodeResponse;
import com.instacommerce.wallet.service.ReferralService;
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
@RequestMapping("/referral")
public class ReferralController {
    private final ReferralService referralService;

    public ReferralController(ReferralService referralService) {
        this.referralService = referralService;
    }

    @GetMapping("/code")
    public ResponseEntity<ReferralCodeResponse> getCode(Authentication auth) {
        UUID userId = UUID.fromString(auth.getName());
        return ResponseEntity.ok(referralService.getOrGenerateCode(userId));
    }

    @PostMapping("/redeem")
    public ResponseEntity<Void> redeemReferral(Authentication auth,
                                                @Valid @RequestBody RedeemReferralRequest request) {
        UUID userId = UUID.fromString(auth.getName());
        referralService.redeemReferral(request.code(), userId);
        return ResponseEntity.ok().build();
    }
}
