package com.instacommerce.payment.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;

/**
 * Annotation-level guard: every {@code @Lock(PESSIMISTIC_WRITE)} method in the
 * payment-service repository layer MUST carry a {@code @QueryHints} with
 * {@code jakarta.persistence.lock.timeout = "5000"}.
 *
 * <p>This prevents indefinite row-lock waits that cascade into connection-pool
 * and thread-pool exhaustion under contention.
 */
class PaymentRepositoryLockTimeoutTest {

    private static final String EXPECTED_HINT_NAME = "jakarta.persistence.lock.timeout";
    private static final String EXPECTED_HINT_VALUE = "5000";

    /**
     * All repository interfaces that may contain pessimistic lock queries.
     * Add new repository classes here if they introduce {@code @Lock} methods.
     */
    private static final List<Class<?>> REPOSITORY_CLASSES = List.of(
        PaymentRepository.class,
        RefundRepository.class
    );

    @Test
    void allPessimisticWriteLocksMustHaveLockTimeout() {
        List<String> violations = new ArrayList<>();

        for (Class<?> repoClass : REPOSITORY_CLASSES) {
            for (Method method : repoClass.getDeclaredMethods()) {
                Lock lockAnnotation = method.getAnnotation(Lock.class);
                if (lockAnnotation == null || lockAnnotation.value() != LockModeType.PESSIMISTIC_WRITE) {
                    continue;
                }

                QueryHints queryHints = method.getAnnotation(QueryHints.class);
                if (queryHints == null) {
                    violations.add(repoClass.getSimpleName() + "." + method.getName()
                        + " has @Lock(PESSIMISTIC_WRITE) but no @QueryHints");
                    continue;
                }

                boolean found = false;
                for (QueryHint hint : queryHints.value()) {
                    if (EXPECTED_HINT_NAME.equals(hint.name())
                            && EXPECTED_HINT_VALUE.equals(hint.value())) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    violations.add(repoClass.getSimpleName() + "." + method.getName()
                        + " has @Lock(PESSIMISTIC_WRITE) but @QueryHints is missing "
                        + EXPECTED_HINT_NAME + " = " + EXPECTED_HINT_VALUE);
                }
            }
        }

        assertTrue(violations.isEmpty(),
            "Pessimistic-lock methods without proper lock timeout:\n  • "
                + String.join("\n  • ", violations));
    }

    @Test
    void paymentRepositoryHasPessimisticLockMethods() {
        // Sanity check: the repository must actually contain the methods we expect.
        // If somebody renames them without updating this test, this fails loudly.
        boolean hasPspRefMethod = false;
        boolean hasIdMethod = false;
        for (Method method : PaymentRepository.class.getDeclaredMethods()) {
            if ("findByPspReferenceForUpdate".equals(method.getName())) {
                hasPspRefMethod = true;
            }
            if ("findByIdForUpdate".equals(method.getName())) {
                hasIdMethod = true;
            }
        }
        assertTrue(hasPspRefMethod,
            "Expected PaymentRepository.findByPspReferenceForUpdate to exist");
        assertTrue(hasIdMethod,
            "Expected PaymentRepository.findByIdForUpdate to exist");
    }
}
