package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.domain.CompensationDlq;
import com.instacommerce.checkout.repository.CompensationDlqRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CompensationDlqActivityImpl implements CompensationDlqActivity {

    private static final Logger log = LoggerFactory.getLogger(CompensationDlqActivityImpl.class);

    private final CompensationDlqRepository dlqRepository;

    public CompensationDlqActivityImpl(CompensationDlqRepository dlqRepository) {
        this.dlqRepository = dlqRepository;
    }

    @Override
    @Transactional
    public void recordFailedCompensation(String workflowId, String activityName, String targetId, String reason, String payload) {
        log.error("CRITICAL_SAGA_COMPENSATION_FAILURE: workflowId={}, activity={}, targetId={}, reason={}",
            workflowId, activityName, targetId, reason);

        CompensationDlq record = new CompensationDlq(workflowId, activityName, targetId, reason, payload);
        dlqRepository.save(record);
    }
}
