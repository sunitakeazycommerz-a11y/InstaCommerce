package com.instacommerce.catalog.service;

import com.instacommerce.catalog.domain.model.OutboxEvent;
import com.instacommerce.catalog.repository.OutboxEventRepository;
import java.util.ArrayList;
import java.util.List;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublishJob {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublishJob.class);

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    public OutboxPublishJob(OutboxEventRepository outboxEventRepository,
                            OutboxEventPublisher outboxEventPublisher) {
        this.outboxEventRepository = outboxEventRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Scheduled(fixedDelayString = "${catalog.outbox.publish-delay-ms:1000}")
    @SchedulerLock(name = "outbox-publish", lockAtLeastFor = "PT5S", lockAtMostFor = "PT1M")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findTop100BySentFalseOrderByCreatedAtAsc();
        if (events.isEmpty()) {
            return;
        }
        List<OutboxEvent> published = new ArrayList<>();
        for (OutboxEvent event : events) {
            try {
                outboxEventPublisher.publish(event);
                event.setSent(true);
                published.add(event);
            } catch (RuntimeException ex) {
                log.error("Failed to publish outbox event {}", event.getId(), ex);
            }
        }
        if (!published.isEmpty()) {
            outboxEventRepository.saveAll(published);
        }
    }
}
