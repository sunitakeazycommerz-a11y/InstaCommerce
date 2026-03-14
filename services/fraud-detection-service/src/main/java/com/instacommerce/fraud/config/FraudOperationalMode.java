package com.instacommerce.fraud.config;

/**
 * Controls the global fraud-detection operating mode. Switchable via
 * config-feature-flag-service or environment variable to allow
 * immediate operator override during model degradation.
 */
public enum FraudOperationalMode {
    /** Fully automated: model score drives block/allow decisions. */
    AUTO_BLOCK,
    /** Model scores are computed but all flagged transactions go to manual review queue. */
    MANUAL_REVIEW,
    /** Model runs in shadow mode: scores are logged but never enforce decisions. */
    SHADOW,
    /** Emergency bypass: all transactions pass. Use only during total model failure. */
    PASS_THROUGH
}
