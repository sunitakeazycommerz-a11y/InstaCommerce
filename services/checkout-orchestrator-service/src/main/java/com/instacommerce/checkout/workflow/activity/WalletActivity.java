package com.instacommerce.checkout.workflow.activity;

import com.instacommerce.checkout.dto.WalletDebitResult;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface WalletActivity {

    @ActivityMethod
    WalletDebitResult debitWallet(String userId, long amountCents, String referenceId, String description);

    @ActivityMethod
    void refundWallet(String userId, long amountCents, String referenceId, String description);
}
