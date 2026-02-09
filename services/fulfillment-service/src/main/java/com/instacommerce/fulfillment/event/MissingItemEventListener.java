package com.instacommerce.fulfillment.event;

import com.instacommerce.fulfillment.service.SubstitutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class MissingItemEventListener {
    private static final Logger logger = LoggerFactory.getLogger(MissingItemEventListener.class);

    private final SubstitutionService substitutionService;

    public MissingItemEventListener(SubstitutionService substitutionService) {
        this.substitutionService = substitutionService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMissingItem(MissingItemEvent event) {
        logger.info("Processing missing item stock release and refund for reference {} after TX commit",
            event.referenceId());
        substitutionService.releaseStockAndRefund(event);
    }
}
