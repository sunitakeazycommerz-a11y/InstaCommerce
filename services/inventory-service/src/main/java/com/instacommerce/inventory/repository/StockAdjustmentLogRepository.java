package com.instacommerce.inventory.repository;

import com.instacommerce.inventory.domain.model.StockAdjustmentLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockAdjustmentLogRepository extends JpaRepository<StockAdjustmentLog, Long> {
}
