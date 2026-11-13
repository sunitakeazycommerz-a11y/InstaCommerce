package com.instacommerce.fraud.service;

import com.instacommerce.fraud.config.FraudOperationalMode;
import com.instacommerce.fraud.domain.model.FraudAction;
import com.instacommerce.fraud.domain.model.FraudSignal;
import com.instacommerce.fraud.dto.request.FraudCheckRequest;
import com.instacommerce.fraud.dto.response.FraudCheckResponse;
import com.instacommerce.fraud.repository.FraudRuleRepository;
import com.instacommerce.fraud.repository.FraudSignalRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FraudScoringTransactionBoundaryTest {

    static class TxState {
        boolean active;
        boolean rollbackOnly;
    }

    static class RecordingTransactionManager extends AbstractPlatformTransactionManager {
        final AtomicInteger begun = new AtomicInteger();
        final AtomicInteger committed = new AtomicInteger();
        final AtomicInteger rolledBack = new AtomicInteger();
        private final ThreadLocal<Deque<TxState>> stack = ThreadLocal.withInitial(ArrayDeque::new);

        @Override
        protected Object doGetTransaction() {
            TxState current = stack.get().peek();
            return current != null ? current : new TxState();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return ((TxState) transaction).active;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            TxState state = new TxState();
            state.active = true;
            stack.get().push(state);
            begun.incrementAndGet();
        }

        @Override
        protected Object doSuspend(Object transaction) {
            return stack.get().pop();
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            stack.get().push((TxState) suspendedResources);
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((TxState) status.getTransaction()).rollbackOnly = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            stack.get().poll();
            committed.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            stack.get().poll();
            rolledBack.incrementAndGet();
        }
    }

    @Test
    void innerPersistenceFailureDoesNotTriggerUnexpectedRollbackInOuterTransaction() {
        RecordingTransactionManager txManager = new RecordingTransactionManager();
        FraudRuleRepository fraudRuleRepository = mock(FraudRuleRepository.class);
        FraudSignalRepository fraudSignalRepository = mock(FraudSignalRepository.class);
        RuleEvaluationService ruleEvaluationService = mock(RuleEvaluationService.class);
        BlocklistService blocklistService = mock(BlocklistService.class);
        OutboxService outboxService = mock(OutboxService.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

        when(blocklistService.isBlocked(anyString(), anyString()))
                .thenThrow(new RuntimeException("Redis timeout"));
        when(fraudSignalRepository.save(any(FraudSignal.class)))
                .thenThrow(new RuntimeException("DB write failure"));

        FraudScoringService service = new FraudScoringService(
                fraudRuleRepository,
                fraudSignalRepository,
                ruleEvaluationService,
                blocklistService,
                outboxService,
                redisTemplate,
                new SimpleMeterRegistry(),
                txManager,
                FraudOperationalMode.AUTO_BLOCK
        );

        FraudCheckRequest request = new FraudCheckRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                25000L,
                "device-tx",
                "198.51.100.2",
                12.9716,
                77.5946,
                1,
                "CARD",
                false
        );

        TransactionTemplate outerTemplate = new TransactionTemplate(txManager);
        FraudCheckResponse response = outerTemplate.execute(status -> {
            FraudCheckResponse scored = service.scoreTransaction(request);
            assertThat(status.isRollbackOnly()).isFalse();
            return scored;
        });

        assertThat(response).isNotNull();
        assertThat(response.action()).isEqualTo(FraudAction.BLOCK.name());
        assertThat(response.signalId()).isNull();
        assertThat(txManager.begun.get()).isEqualTo(2);
        assertThat(txManager.rolledBack.get()).isEqualTo(1);
        assertThat(txManager.committed.get()).isEqualTo(1);
    }
}
