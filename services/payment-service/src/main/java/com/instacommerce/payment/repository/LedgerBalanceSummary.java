package com.instacommerce.payment.repository;

/**
 * Interface-based projection returned by aggregate ledger queries.
 * Each row represents the total amount_cents for one (referenceType, entryType) pair.
 */
public interface LedgerBalanceSummary {
    String getReferenceType();

    String getEntryType();

    long getTotalAmountCents();
}
