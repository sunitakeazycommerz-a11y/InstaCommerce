package com.instacommerce.wallet.service;

import com.instacommerce.wallet.config.WalletProperties;
import com.instacommerce.wallet.domain.model.ReferralCode;
import com.instacommerce.wallet.domain.model.ReferralRedemption;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.dto.response.ReferralCodeResponse;
import com.instacommerce.wallet.exception.ApiException;
import com.instacommerce.wallet.repository.ReferralCodeRepository;
import com.instacommerce.wallet.repository.ReferralRedemptionRepository;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final WalletProperties walletProperties;

    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralRedemptionRepository redemptionRepository,
                           WalletService walletService) {
        this(referralCodeRepository, redemptionRepository, walletService, new WalletProperties());
    }

    @Autowired
    public ReferralService(ReferralCodeRepository referralCodeRepository,
                           ReferralRedemptionRepository redemptionRepository,
                           WalletService walletService,
                           WalletProperties walletProperties) {
        this.referralCodeRepository = referralCodeRepository;
        this.redemptionRepository = redemptionRepository;
        this.walletService = walletService;
        this.walletProperties = walletProperties != null ? walletProperties : new WalletProperties();
    }

    @Transactional
    public ReferralCodeResponse getOrGenerateCode(UUID userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        ReferralCode code = referralCodeRepository.findByUserId(userId)
            .orElseGet(() -> generateCode(userId));
        return toResponse(code);
    }

    @Transactional
    public void redeemReferral(String code, UUID newUserId) {
        if (newUserId == null) {
            throw new IllegalArgumentException("newUserId must not be null");
        }
        if (code == null || code.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REFERRAL_CODE",
                "Referral code must not be blank");
        }
        String normalizedCode = code.trim().toUpperCase(Locale.ROOT);
        ReferralCode referralCode = referralCodeRepository.findByCodeForUpdate(normalizedCode)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REFERRAL_NOT_FOUND",
                "Referral code not found: " + normalizedCode));

        if (!referralCode.isActive()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "REFERRAL_INACTIVE",
                "Referral code is no longer active");
        }
        int configuredCap = walletProperties.getLoyalty().getMaxReferralRedemptionsPerReferrer();
        int effectiveMaxUses = configuredCap > 0
            ? Math.min(referralCode.getMaxUses(), configuredCap)
            : referralCode.getMaxUses();
        if (referralCode.getUses() >= effectiveMaxUses) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "REFERRAL_MAX_USES",
                "Referral code has reached maximum uses");
        }
        if (referralCode.getUserId().equals(newUserId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "REFERRAL_SELF_USE",
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
        try {
            ReferralRedemption saved = redemptionRepository.saveAndFlush(redemption);
            if (saved != null) {
                redemption = saved;
            }
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "REFERRAL_ALREADY_USED",
                "User has already used a referral code");
        }

        referralCode.setUses(referralCode.getUses() + 1);
        referralCodeRepository.save(referralCode);

        long rewardCents = referralCode.getRewardCents();
        if (rewardCents <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_REFERRAL_REWARD",
                "Referral reward amount must be positive");
        }
        UUID redemptionId = redemption.getId() != null ? redemption.getId() : UUID.randomUUID();
        String refId = "referral-" + redemptionId.toString();

        // Credit referrer wallet
        walletService.credit(referralCode.getUserId(), rewardCents,
            ReferenceType.REFERRAL, refId + "-referrer", "Referral reward: new user signup");

        // Credit referee wallet
        walletService.credit(newUserId, rewardCents,
            ReferenceType.REFERRAL, refId + "-referee", "Welcome bonus: referral signup");

        log.info("Referral redeemed: code={} referrer={} referee={}", normalizedCode, referralCode.getUserId(), newUserId);
    }

    private ReferralCode generateCode(UUID userId) {
        ReferralCode referralCode = new ReferralCode();
        referralCode.setUserId(userId);
        referralCode.setCode(generateUniqueCode());
        long rewardCents = walletProperties.getReferral().getRewardCents();
        if (rewardCents > 0) {
            referralCode.setRewardCents(rewardCents);
        }
        int cap = walletProperties.getLoyalty().getMaxReferralRedemptionsPerReferrer();
        if (cap > 0) {
            referralCode.setMaxUses(Math.min(referralCode.getMaxUses(), cap));
        }
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
