package com.instacommerce.pricing.service;

import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.domain.PromotionRedemption;
import com.instacommerce.pricing.dto.request.CreatePromotionRequest;
import com.instacommerce.pricing.dto.request.UpdatePromotionRequest;
import com.instacommerce.pricing.dto.response.PromotionResponse;
import com.instacommerce.pricing.exception.InvalidPromotionException;
import com.instacommerce.pricing.exception.PromotionNotFoundException;
import com.instacommerce.pricing.repository.PromotionRedemptionRepository;
import com.instacommerce.pricing.repository.PromotionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionService {
    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

    private final PromotionRepository promotionRepository;
    private final OutboxService outboxService;
    private final PromotionRedemptionRepository promotionRedemptionRepository;

    @Autowired
    public PromotionService(PromotionRepository promotionRepository,
                            OutboxService outboxService,
                            PromotionRedemptionRepository promotionRedemptionRepository) {
        this.promotionRepository = promotionRepository;
        this.outboxService = outboxService;
        this.promotionRedemptionRepository = promotionRedemptionRepository;
    }

    public PromotionService(PromotionRepository promotionRepository, OutboxService outboxService) {
        this(promotionRepository, outboxService, null);
    }

    @Cacheable(value = "activePromotions", key = "'all'")
    @Transactional(readOnly = true)
    public List<Promotion> findActivePromotions() {
        return promotionRepository.findActivePromotions(Instant.now());
    }

    public long calculateDiscount(Promotion promotion, long subtotalCents) {
        if (promotion == null || subtotalCents <= 0 || promotion.getDiscountValue() == null
                || promotion.getDiscountValue().signum() <= 0) {
            return 0;
        }
        long discount;
        if ("PERCENTAGE".equalsIgnoreCase(promotion.getDiscountType())) {
            BigDecimal clampedPercent = promotion.getDiscountValue().compareTo(ONE_HUNDRED) > 0
                    ? ONE_HUNDRED
                    : promotion.getDiscountValue();
            discount = BigDecimal.valueOf(subtotalCents)
                    .multiply(clampedPercent)
                    .divide(ONE_HUNDRED, 0, RoundingMode.HALF_UP)
                    .longValue();
        } else {
            discount = Math.max(0, promotion.getDiscountValue().longValue());
        }

        if (promotion.getMaxDiscountCents() != null) {
            discount = Math.min(discount, Math.max(0, promotion.getMaxDiscountCents()));
        }
        return Math.min(discount, subtotalCents);
    }

    public boolean isEligibleAt(Promotion promotion, Instant now, long userRedemptionCount) {
        if (promotion == null || !promotion.isActive()) {
            return false;
        }
        if (promotion.getStartAt() != null && now.isBefore(promotion.getStartAt())) {
            return false;
        }
        if (promotion.getEndAt() != null && !now.isBefore(promotion.getEndAt())) {
            return false;
        }
        if (promotion.getMaxUses() != null && promotion.getCurrentUses() >= promotion.getMaxUses()) {
            return false;
        }
        Integer maxPerUser = promotion.getMaxRedemptionsPerUser();
        return maxPerUser == null || maxPerUser <= 0 || userRedemptionCount < maxPerUser;
    }

    @Transactional(readOnly = true)
    public long countUserRedemptions(UUID promotionId, UUID userId) {
        if (promotionId == null || userId == null || promotionRedemptionRepository == null) {
            return 0;
        }
        return promotionRedemptionRepository.countByPromotionIdAndUserId(promotionId, userId);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Long> findUserRedemptionCounts(UUID userId, Collection<UUID> promotionIds) {
        if (userId == null || promotionIds == null || promotionIds.isEmpty()
                || promotionRedemptionRepository == null) {
            return Collections.emptyMap();
        }
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : promotionRedemptionRepository.countByUserIdAndPromotionIds(userId, promotionIds)) {
            if (row != null && row.length >= 2 && row[0] instanceof UUID promoId && row[1] instanceof Number cnt) {
                counts.put(promoId, cnt.longValue());
            }
        }
        return counts;
    }

    @Transactional(readOnly = true)
    public PromotionResponse getById(UUID id) {
        Promotion p = promotionRepository.findById(id)
                .orElseThrow(() -> new PromotionNotFoundException(id.toString()));
        return toResponse(p);
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> listAll() {
        return promotionRepository.findAll().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public PromotionResponse create(CreatePromotionRequest request) {
        String normalizedType = normalizeAndValidateDiscountType(request.discountType());
        validatePromotionRules(
                normalizedType,
                request.discountValue(),
                request.minOrderCents(),
                request.maxDiscountCents(),
                request.startAt(),
                request.endAt(),
                request.maxUses(),
                request.maxRedemptionsPerUser());

        Promotion p = new Promotion();
        p.setName(request.name().trim());
        p.setDescription(request.description());
        p.setDiscountType(normalizedType);
        p.setDiscountValue(request.discountValue());
        p.setMinOrderCents(request.minOrderCents());
        p.setMaxDiscountCents(request.maxDiscountCents());
        p.setStartAt(request.startAt());
        p.setEndAt(request.endAt());
        p.setMaxUses(request.maxUses());
        if (request.maxRedemptionsPerUser() != null) {
            p.setMaxRedemptionsPerUser(request.maxRedemptionsPerUser());
        }
        if (request.stackableWithCoupon() != null) {
            p.setStackableWithCoupon(request.stackableWithCoupon());
        }
        if (request.priority() != null) {
            p.setPriority(request.priority());
        }
        p.setActive(true);
        Promotion saved = promotionRepository.save(p);
        log.info("Created promotion id={} name={}", saved.getId(), saved.getName());
        PromotionResponse response = toResponse(saved);
        outboxService.recordEvent("pricing", saved.getId().toString(), "PROMOTION_CREATED", response);
        return response;
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public PromotionResponse update(UUID id, UpdatePromotionRequest request) {
        Promotion p = promotionRepository.findById(id)
                .orElseThrow(() -> new PromotionNotFoundException(id.toString()));

        String nextType = request.discountType() != null
                ? normalizeAndValidateDiscountType(request.discountType())
                : p.getDiscountType();
        BigDecimal nextValue = request.discountValue() != null ? request.discountValue() : p.getDiscountValue();
        long nextMinOrder = request.minOrderCents() != null ? request.minOrderCents() : p.getMinOrderCents();
        Long nextMaxDiscount = request.maxDiscountCents() != null ? request.maxDiscountCents() : p.getMaxDiscountCents();
        Instant nextStartAt = request.startAt() != null ? request.startAt() : p.getStartAt();
        Instant nextEndAt = request.endAt() != null ? request.endAt() : p.getEndAt();
        Integer nextMaxUses = request.maxUses() != null ? request.maxUses() : p.getMaxUses();
        Integer nextMaxPerUser = request.maxRedemptionsPerUser() != null
                ? request.maxRedemptionsPerUser()
                : p.getMaxRedemptionsPerUser();

        validatePromotionRules(nextType, nextValue, nextMinOrder, nextMaxDiscount,
                nextStartAt, nextEndAt, nextMaxUses, nextMaxPerUser);

        if (request.name() != null) p.setName(request.name().trim());
        if (request.description() != null) p.setDescription(request.description());
        p.setDiscountType(nextType);
        p.setDiscountValue(nextValue);
        p.setMinOrderCents(nextMinOrder);
        p.setMaxDiscountCents(nextMaxDiscount);
        p.setStartAt(nextStartAt);
        p.setEndAt(nextEndAt);
        if (request.active() != null) p.setActive(request.active());
        p.setMaxUses(nextMaxUses);
        p.setMaxRedemptionsPerUser(nextMaxPerUser);
        if (request.stackableWithCoupon() != null) p.setStackableWithCoupon(request.stackableWithCoupon());
        if (request.priority() != null) p.setPriority(request.priority());

        Promotion saved = promotionRepository.save(p);
        log.info("Updated promotion id={}", saved.getId());
        PromotionResponse response = toResponse(saved);
        outboxService.recordEvent("pricing", saved.getId().toString(), "PROMOTION_UPDATED", response);
        return response;
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public boolean recordPromotionUsage(UUID promotionId) {
        int updated = promotionRepository.incrementUsage(promotionId);
        if (updated == 0) {
            log.warn("Promotion usage increment failed (limit reached or not found): {}", promotionId);
            return false;
        }
        return true;
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public boolean recordPromotionUsage(UUID promotionId, UUID userId) {
        return recordPromotionUsage(promotionId, userId, null, 0L);
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public boolean recordPromotionUsage(UUID promotionId, UUID userId, UUID orderId, long discountCents) {
        var promoOpt = promotionRepository.findByIdForUpdate(promotionId);
        if (promoOpt.isEmpty()) {
            log.warn("Promotion not found for update: {}", promotionId);
            return false;
        }
        Promotion promo = promoOpt.get();
        Instant now = Instant.now();
        if (!promo.isActive()
                || (promo.getStartAt() != null && now.isBefore(promo.getStartAt()))
                || (promo.getEndAt() != null && !now.isBefore(promo.getEndAt()))) {
            log.warn("Promotion inactive or outside window for promo={}", promotionId);
            return false;
        }
        if (promo.getMaxUses() != null && promo.getCurrentUses() >= promo.getMaxUses()) {
            log.warn("Promotion max uses exceeded for promo={}", promotionId);
            return false;
        }

        if (userId != null && promotionRedemptionRepository != null) {
            if (orderId != null && promotionRedemptionRepository
                    .findByPromotionIdAndUserIdAndOrderId(promotionId, userId, orderId)
                    .isPresent()) {
                log.info("Idempotent promotion redemption replay promo={} userId={} orderId={}",
                        promotionId, userId, orderId);
                return true;
            }
            Integer perUserLimit = promo.getMaxRedemptionsPerUser();
            if (perUserLimit != null && perUserLimit > 0) {
                long existingUserCount = promotionRedemptionRepository
                        .countByPromotionIdAndUserId(promotionId, userId);
                if (existingUserCount >= perUserLimit) {
                    log.warn("Promotion per-user limit reached promo={} userId={}", promotionId, userId);
                    return false;
                }
            }
            PromotionRedemption redemption = new PromotionRedemption();
            redemption.setPromotionId(promotionId);
            redemption.setUserId(userId);
            redemption.setOrderId(orderId);
            redemption.setDiscountCents(Math.max(0, discountCents));
            try {
                promotionRedemptionRepository.save(redemption);
            } catch (DataIntegrityViolationException ex) {
                if (orderId != null && promotionRedemptionRepository
                        .findByPromotionIdAndUserIdAndOrderId(promotionId, userId, orderId)
                        .isPresent()) {
                    return true;
                }
                log.warn("Promotion redemption uniqueness violation promo={} userId={}", promotionId, userId);
                return false;
            }
        }

        return recordPromotionUsage(promotionId);
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public void rollbackPromotionUsage(UUID promotionId) {
        promotionRepository.decrementUsage(promotionId);
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public void rollbackPromotionUsage(UUID promotionId, UUID userId, UUID orderId) {
        if (userId != null && promotionRedemptionRepository != null) {
            var redemptionOpt = orderId != null
                    ? promotionRedemptionRepository.findByPromotionIdAndUserIdAndOrderId(promotionId, userId, orderId)
                    : promotionRedemptionRepository.findTopByPromotionIdAndUserIdOrderByRedeemedAtDesc(promotionId, userId);
            if (redemptionOpt.isPresent()) {
                promotionRedemptionRepository.delete(redemptionOpt.get());
                promotionRepository.decrementUsage(promotionId);
            }
            return;
        }
        promotionRepository.decrementUsage(promotionId);
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public void delete(UUID id) {
        Promotion p = promotionRepository.findById(id)
                .orElseThrow(() -> new PromotionNotFoundException(id.toString()));
        p.setActive(false);
        Promotion saved = promotionRepository.save(p);
        outboxService.recordEvent("pricing", id.toString(), "PROMOTION_DEACTIVATED", toResponse(saved));
        log.info("Deactivated promotion id={}", id);
    }

    private String normalizeAndValidateDiscountType(String discountType) {
        if (discountType == null || discountType.isBlank()) {
            throw new InvalidPromotionException("Discount type is required");
        }
        String normalized = discountType.trim().toUpperCase(Locale.ROOT);
        if (!"PERCENTAGE".equals(normalized) && !"FIXED_AMOUNT".equals(normalized) && !"FIXED".equals(normalized)) {
            throw new InvalidPromotionException("Unsupported discount type: " + discountType);
        }
        return normalized;
    }

    private void validatePromotionRules(String discountType,
                                        BigDecimal discountValue,
                                        long minOrderCents,
                                        Long maxDiscountCents,
                                        Instant startAt,
                                        Instant endAt,
                                        Integer maxUses,
                                        Integer maxRedemptionsPerUser) {
        if (discountValue == null || discountValue.signum() <= 0) {
            throw new InvalidPromotionException("Discount value must be positive");
        }
        if ("PERCENTAGE".equals(discountType) && discountValue.compareTo(ONE_HUNDRED) > 0) {
            throw new InvalidPromotionException("Percentage discount cannot exceed 100");
        }
        if (minOrderCents < 0) {
            throw new InvalidPromotionException("Minimum order amount cannot be negative");
        }
        if (maxDiscountCents != null && maxDiscountCents < 0) {
            throw new InvalidPromotionException("Maximum discount cap cannot be negative");
        }
        if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
            throw new InvalidPromotionException("Promotion endAt must be strictly after startAt");
        }
        if (maxUses != null && maxUses <= 0) {
            throw new InvalidPromotionException("maxUses must be positive");
        }
        if (maxRedemptionsPerUser != null && maxRedemptionsPerUser <= 0) {
            throw new InvalidPromotionException("maxRedemptionsPerUser must be positive");
        }
    }

    private PromotionResponse toResponse(Promotion p) {
        return new PromotionResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getDiscountType(),
                p.getDiscountValue(),
                p.getMinOrderCents(),
                p.getMaxDiscountCents(),
                p.getStartAt(),
                p.getEndAt(),
                p.isActive(),
                p.getMaxUses(),
                p.getCurrentUses(),
                p.getMaxRedemptionsPerUser(),
                p.isStackableWithCoupon(),
                p.getPriority(),
                p.getCreatedAt());
    }
}
