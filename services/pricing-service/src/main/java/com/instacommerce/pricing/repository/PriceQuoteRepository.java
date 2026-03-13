package com.instacommerce.pricing.repository;

import com.instacommerce.pricing.domain.PriceQuote;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PriceQuoteRepository extends JpaRepository<PriceQuote, UUID> {
}
