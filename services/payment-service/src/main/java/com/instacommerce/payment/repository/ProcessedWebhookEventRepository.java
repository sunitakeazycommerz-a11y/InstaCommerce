package com.instacommerce.payment.repository;

import com.instacommerce.payment.domain.model.ProcessedWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedWebhookEventRepository extends JpaRepository<ProcessedWebhookEvent, String> {
}
