package com.instacommerce.checkout.workflow.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface CompensationDlqActivity {

    @ActivityMethod
    void recordFailedCompensation(String workflowId, String activityName, String targetId, String reason, String payload);
}
