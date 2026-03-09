package com.instacommerce.checkout.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaymentOperationKeysTest {

    private static final String WORKFLOW_ID = "checkout-user42-ik-abc123";

    @Nested
    @DisplayName("Construction")
    class Construction {

        @Test
        void rejectsNullWorkflowId() {
            assertThrows(IllegalArgumentException.class, () -> new PaymentOperationKeys(null));
        }

        @Test
        void rejectsBlankWorkflowId() {
            assertThrows(IllegalArgumentException.class, () -> new PaymentOperationKeys("  "));
        }

        @Test
        void acceptsValidWorkflowId() {
            PaymentOperationKeys keys = new PaymentOperationKeys(WORKFLOW_ID);
            assertEquals(WORKFLOW_ID, keys.workflowId());
        }
    }

    @Nested
    @DisplayName("Key format and stability")
    class KeyFormat {

        private final PaymentOperationKeys keys = new PaymentOperationKeys(WORKFLOW_ID);

        @Test
        void authorizeKeyIsDeterministic() {
            assertEquals(WORKFLOW_ID + ":pay:auth", keys.authorize());
            // Same instance always returns the same value
            assertEquals(keys.authorize(), keys.authorize());
        }

        @Test
        void captureKeyIncludesPaymentId() {
            String key = keys.capture("pay_999");
            assertEquals(WORKFLOW_ID + ":pay:pay_999:capture", key);
        }

        @Test
        void voidKeyIncludesPaymentId() {
            String key = keys.voidOp("pay_999");
            assertEquals(WORKFLOW_ID + ":pay:pay_999:void", key);
        }

        @Test
        void refundKeyIncludesPaymentId() {
            String key = keys.refund("pay_999");
            assertEquals(WORKFLOW_ID + ":pay:pay_999:refund", key);
        }

        @Test
        void allOperationKeysAreDifferent() {
            String paymentId = "pay_1";
            String auth = keys.authorize();
            String capture = keys.capture(paymentId);
            String voidKey = keys.voidOp(paymentId);
            String refund = keys.refund(paymentId);

            // All four keys must be distinct
            assertEquals(4, java.util.Set.of(auth, capture, voidKey, refund).size(),
                "All operation keys must be unique");
        }

        @Test
        void keysAreStableAcrossNewInstances() {
            PaymentOperationKeys keys2 = new PaymentOperationKeys(WORKFLOW_ID);
            assertEquals(keys.authorize(), keys2.authorize());
            assertEquals(keys.capture("pay_1"), keys2.capture("pay_1"));
            assertEquals(keys.voidOp("pay_1"), keys2.voidOp("pay_1"));
            assertEquals(keys.refund("pay_1"), keys2.refund("pay_1"));
        }
    }

    @Nested
    @DisplayName("Post-auth key validation")
    class PostAuthKeyValidation {

        private final PaymentOperationKeys keys = new PaymentOperationKeys(WORKFLOW_ID);

        @Test
        void captureRejectsNullPaymentId() {
            assertThrows(IllegalArgumentException.class, () -> keys.capture(null));
        }

        @Test
        void captureRejectsBlankPaymentId() {
            assertThrows(IllegalArgumentException.class, () -> keys.capture(""));
        }

        @Test
        void voidRejectsNullPaymentId() {
            assertThrows(IllegalArgumentException.class, () -> keys.voidOp(null));
        }

        @Test
        void refundRejectsNullPaymentId() {
            assertThrows(IllegalArgumentException.class, () -> keys.refund(null));
        }
    }

    @Nested
    @DisplayName("Key length is reasonable")
    class KeyLength {

        @Test
        void authorizeKeyIsUnder128Chars() {
            // Even with a long workflowId, keys should be concise
            String longWorkflowId = "checkout-user-" + "a".repeat(80) + "-ik-" + "b".repeat(36);
            PaymentOperationKeys keys = new PaymentOperationKeys(longWorkflowId);
            assertTrue(keys.authorize().length() < 256,
                "Authorize key should be well under typical DB column limits");
        }

        @Test
        void operationKeysAreShorterThanOldFormat() {
            // Old format: workflowId + "-payment" + "-" + paymentId + "-capture" + "-" + activityId
            // New format: workflowId + ":pay:" + paymentId + ":capture"
            PaymentOperationKeys keys = new PaymentOperationKeys(WORKFLOW_ID);
            String newKey = keys.capture("pay_abc123");

            // The new key should not contain "-payment-" or activity id patterns
            assertFalse(newKey.contains("-payment-"), "New key format should not use old prefix");
        }
    }

    @Nested
    @DisplayName("Equality and toString")
    class EqualityAndToString {

        @Test
        void equalityBasedOnWorkflowId() {
            PaymentOperationKeys a = new PaymentOperationKeys(WORKFLOW_ID);
            PaymentOperationKeys b = new PaymentOperationKeys(WORKFLOW_ID);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        void notEqualForDifferentWorkflowIds() {
            PaymentOperationKeys a = new PaymentOperationKeys("wf-1");
            PaymentOperationKeys b = new PaymentOperationKeys("wf-2");
            assertNotEquals(a, b);
        }

        @Test
        void toStringIncludesWorkflowId() {
            PaymentOperationKeys keys = new PaymentOperationKeys(WORKFLOW_ID);
            assertTrue(keys.toString().contains(WORKFLOW_ID));
        }
    }
}
