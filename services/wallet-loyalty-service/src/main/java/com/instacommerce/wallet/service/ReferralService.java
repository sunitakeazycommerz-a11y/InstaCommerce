package com.instacommerce.wallet.service;

import com.instacommerce.wallet.domain.model.ReferralCode;
import com.instacommerce.wallet.domain.model.ReferralRedemption;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.dto.response.ReferralCodeResponse;
import com.instacommerce.wallet.exception.ApiException;
import com.instacommerce.wallet.repository.ReferralCodeRepository;
import com.instacommerce.wallet.repository.ReferralRedemptionRepository;
import java.security.SecureRandom;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReferralService {
    private static final Logger log = LoggerFactory.getLogger(ReferralService.class);
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReferralCodeRepository referralCodeRepository;
    private final ReferralRedemptionRepository redemptionRepository;
    private final WalletService walletService;

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralRedemptionRepository redemptionRepository,
                           WalletService walletService) {
        this.referralCodeRepository = referralCodeRepository;
        this.redemptionRepository = redemptionRepository;
        this.walletService = walletService;
    }

    @Transactional
    public ReferralCodeResponse getOrGenerateCode(UUID userId) {
        ReferralCode code = referralCodeRepository.findByUserId(userId)
            .orElseGet(() -> generateCode(userId));
        return toResponse(code);
    }

    @Transactional
    public void redeemReferral(String code, UUID newUserId) {
        ReferralCode referralCode = referralCodeRepository.findByCode(code.toUpperCase())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REFERRAL_NOT_FOUND",
                "Referral code not found: " + code));

        if (!referralCode.isActive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFERRAL_INACTIVE",
                "Referral code is no longer active");
        }
        if (referralCode.getUses() >= referralCode.getMaxUses()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFERRAL_MAX_USES",
                "Referral code has reached maximum uses");
        }
        if (referralCode.getUserId().equals(newUserId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REFERRAL_SELF_USE",
                "Cannot use your own referral code");
        }
        redemptionRepository.findByReferredUserId(newUserId).ifPresent(existing -> {
            throw new ApiException(HttpStatus.CONFLICT, "REFERRAL_ALREADY_USED",
                "User has already used a referral code");
        });

        ReferralRedemption redemption = new ReferralRedemption();
        redemption.setReferralCode(referralCode);
        redemption.setReferredUserId(newUserId);
        redemption.setRewardCredited(true);
        redemptionRepository.save(redemption);

        referralCode.setUses(referralCode.getUses() + 1);
        referralCodeRepository.save(referralCode);

        long rewardCents = referralCode.getRewardCents();
        String refId = "referral-" + redemption.getId().toString();

        // Credit referrer wallet
        walletService.credit(referralCode.getUserId(), rewardCents,
            ReferenceType.REFERRAL, refId + "-referrer", "Referral reward: new user signup");

        // Credit referee wallet
        walletService.credit(newUserId, rewardCents,
            ReferenceType.REFERRAL, refId + "-referee", "Welcome bonus: referral signup");

        log.info("Referral redeemed: code={} referrer={} referee={}", code, referralCode.getUserId(), newUserId);
    }

    private ReferralCode generateCode(UUID userId) {
        ReferralCode referralCode = new ReferralCode();
        referralCode.setUserId(userId);
        referralCode.setCode(generateUniqueCode());
        return referralCodeRepository.save(referralCode);
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            }
            String code = sb.toString();
            if (referralCodeRepository.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new IllegalStateException("Failed to generate unique referral code after 10 attempts");
    }

    private ReferralCodeResponse toResponse(ReferralCode code) {
        return new ReferralCodeResponse(code.getCode(), code.getUses(), code.getMaxUses(), code.getRewardCents());
    }
}
