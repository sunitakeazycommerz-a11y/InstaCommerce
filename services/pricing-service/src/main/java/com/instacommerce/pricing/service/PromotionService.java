package com.instacommerce.pricing.service;

import com.instacommerce.pricing.domain.Promotion;
import com.instacommerce.pricing.dto.request.CreatePromotionRequest;
import com.instacommerce.pricing.dto.request.UpdatePromotionRequest;
import com.instacommerce.pricing.dto.response.PromotionResponse;
import com.instacommerce.pricing.exception.PromotionNotFoundException;
import com.instacommerce.pricing.repository.PromotionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PromotionService {
    private static final Logger log = LoggerFactory.getLogger(PromotionService.class);

    private final PromotionRepository promotionRepository;

    public PromotionService(PromotionRepository promotionRepository) {
        this.promotionRepository = promotionRepository;
    }

    @Cacheable(value = "activePromotions", key = "'all'")
    @Transactional(readOnly = true)
    public List<Promotion> findActivePromotions() {
        return promotionRepository.findActivePromotions();
    }

    public long calculateDiscount(Promotion promotion, long subtotalCents) {
        long discount;
        if ("PERCENTAGE".equalsIgnoreCase(promotion.getDiscountType())) {
            discount = BigDecimal.valueOf(subtotalCents)
                    .multiply(promotion.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .longValue();
        } else {
            discount = promotion.getDiscountValue().longValue();
        }

        if (promotion.getMaxDiscountCents() != null) {
            discount = Math.min(discount, promotion.getMaxDiscountCents());
        }
        return Math.min(discount, subtotalCents);
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
        Promotion p = new Promotion();
        p.setName(request.name());
        p.setDescription(request.description());
        p.setDiscountType(request.discountType());
        p.setDiscountValue(request.discountValue());
        p.setMinOrderCents(request.minOrderCents());
        p.setMaxDiscountCents(request.maxDiscountCents());
        p.setStartAt(request.startAt());
        p.setEndAt(request.endAt());
        p.setMaxUses(request.maxUses());
        p.setActive(true);
        Promotion saved = promotionRepository.save(p);
        log.info("Created promotion id={} name={}", saved.getId(), saved.getName());
        return toResponse(saved);
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public PromotionResponse update(UUID id, UpdatePromotionRequest request) {
        Promotion p = promotionRepository.findById(id)
                .orElseThrow(() -> new PromotionNotFoundException(id.toString()));

        if (request.name() != null) p.setName(request.name());
        if (request.description() != null) p.setDescription(request.description());
        if (request.discountType() != null) p.setDiscountType(request.discountType());
        if (request.discountValue() != null) p.setDiscountValue(request.discountValue());
        if (request.minOrderCents() != null) p.setMinOrderCents(request.minOrderCents());
        if (request.maxDiscountCents() != null) p.setMaxDiscountCents(request.maxDiscountCents());
        if (request.startAt() != null) p.setStartAt(request.startAt());
        if (request.endAt() != null) p.setEndAt(request.endAt());
        if (request.active() != null) p.setActive(request.active());
        if (request.maxUses() != null) p.setMaxUses(request.maxUses());

        Promotion saved = promotionRepository.save(p);
        log.info("Updated promotion id={}", saved.getId());
        return toResponse(saved);
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
    public void rollbackPromotionUsage(UUID promotionId) {
        promotionRepository.decrementUsage(promotionId);
    }

    @CacheEvict(value = "activePromotions", allEntries = true)
    @Transactional
    public void delete(UUID id) {
        Promotion p = promotionRepository.findById(id)
                .orElseThrow(() -> new PromotionNotFoundException(id.toString()));
        p.setActive(false);
        promotionRepository.save(p);
        log.info("Deactivated promotion id={}", id);
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
                p.getCreatedAt());
    }
}
