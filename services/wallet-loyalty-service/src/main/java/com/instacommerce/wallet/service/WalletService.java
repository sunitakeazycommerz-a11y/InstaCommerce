package com.instacommerce.wallet.service;

import com.instacommerce.wallet.domain.model.Wallet;
import com.instacommerce.wallet.domain.model.WalletTransaction;
import com.instacommerce.wallet.domain.model.WalletTransaction.ReferenceType;
import com.instacommerce.wallet.domain.model.WalletTransaction.Type;
import com.instacommerce.wallet.dto.response.WalletResponse;
import com.instacommerce.wallet.dto.response.WalletTransactionResponse;
import com.instacommerce.wallet.exception.DuplicateTransactionException;
import com.instacommerce.wallet.exception.InsufficientBalanceException;
import com.instacommerce.wallet.exception.WalletNotFoundException;
import com.instacommerce.wallet.repository.WalletRepository;
import com.instacommerce.wallet.repository.WalletTransactionRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {
    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository transactionRepository;
    private final OutboxService outboxService;
    private final WalletLedgerService walletLedgerService;

    public WalletService(WalletRepository walletRepository,
                         WalletTransactionRepository transactionRepository,
                         OutboxService outboxService,
                         WalletLedgerService walletLedgerService) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
        this.outboxService = outboxService;
        this.walletLedgerService = walletLedgerService;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "walletBalance", key = "#userId")
    public WalletResponse getBalance(UUID userId) {
        return walletRepository.findByUserId(userId)
            .map(wallet -> new WalletResponse(wallet.getBalanceCents(), wallet.getCurrency()))
            .orElseGet(() -> new WalletResponse(0L, "INR"));
    }

    @Transactional
    @CacheEvict(value = "walletBalance", key = "#userId")
    public WalletTransactionResponse credit(UUID userId, long amountCents,
                                            ReferenceType refType, String refId, String description) {
        String normalizedRefId = validateMutationArgs(userId, amountCents, refType, refId);
        Optional<WalletTransaction> existing = transactionRepository
            .findByReferenceTypeAndReferenceId(refType, normalizedRefId);
        if (existing.isPresent()) {
            return resolveIdempotentMatch(existing.get(), userId, Type.CREDIT, amountCents, refType, normalizedRefId);
        }

        Wallet wallet = findOrCreateWallet(userId);
        long newBalance = Math.addExact(wallet.getBalanceCents(), amountCents);
        wallet.setBalanceCents(newBalance);
        walletRepository.save(wallet);

        WalletTransaction txn = recordTransaction(
            wallet, Type.CREDIT, amountCents, refType, normalizedRefId, description);
        recordLedgerEntry(wallet.getId(), userId, refType, amountCents, normalizedRefId, true);
        log.info("Credited {} cents to wallet for user={} ref={}/{}", amountCents, userId, refType, normalizedRefId);

        Map<String, Object> creditPayload = new LinkedHashMap<>();
        creditPayload.put("transactionId", txn.getId().toString());
        creditPayload.put("walletId", wallet.getId().toString());
        creditPayload.put("userId", userId.toString());
        creditPayload.put("amountCents", amountCents);
        creditPayload.put("balanceCents", wallet.getBalanceCents());
        creditPayload.put("referenceType", refType.name());
        creditPayload.put("referenceId", normalizedRefId);
        outboxService.publish("Wallet", wallet.getId().toString(), "WalletCredited", creditPayload);
        return toResponse(txn);
    }

    @Transactional
    @CacheEvict(value = "walletBalance", key = "#userId")
    public WalletTransactionResponse debit(UUID userId, long amountCents,
                                           ReferenceType refType, String refId, String description) {
        String normalizedRefId = validateMutationArgs(userId, amountCents, refType, refId);
        Optional<WalletTransaction> existing = transactionRepository
            .findByReferenceTypeAndReferenceId(refType, normalizedRefId);
        if (existing.isPresent()) {
            return resolveIdempotentMatch(existing.get(), userId, Type.DEBIT, amountCents, refType, normalizedRefId);
        }

        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new WalletNotFoundException("Wallet not found for user: " + userId));

        if (wallet.getBalanceCents() < amountCents) {
            throw new InsufficientBalanceException(
                String.format("Insufficient balance: available=%d, requested=%d", wallet.getBalanceCents(), amountCents));
        }

        wallet.setBalanceCents(wallet.getBalanceCents() - amountCents);
        walletRepository.save(wallet);

        WalletTransaction txn = recordTransaction(
            wallet, Type.DEBIT, amountCents, refType, normalizedRefId, description);
        recordLedgerEntry(wallet.getId(), userId, refType, amountCents, normalizedRefId, false);
        log.info("Debited {} cents from wallet for user={} ref={}/{}", amountCents, userId, refType, normalizedRefId);

        Map<String, Object> debitPayload = new LinkedHashMap<>();
        debitPayload.put("transactionId", txn.getId().toString());
        debitPayload.put("walletId", wallet.getId().toString());
        debitPayload.put("userId", userId.toString());
        debitPayload.put("amountCents", amountCents);
        debitPayload.put("balanceCents", wallet.getBalanceCents());
        debitPayload.put("referenceType", refType.name());
        debitPayload.put("referenceId", normalizedRefId);
        outboxService.publish("Wallet", wallet.getId().toString(), "WalletDebited", debitPayload);
        return toResponse(txn);
    }

    @Transactional(readOnly = true)
    public long getMonthlyCashbackCreditedCents(UUID userId, Instant monthStart) {
        if (userId == null || monthStart == null) {
            return 0L;
        }
        return transactionRepository.sumCreditsByUserIdAndReferenceTypeSince(
            userId, ReferenceType.CASHBACK, monthStart);
    }

    private String validateMutationArgs(UUID userId, long amountCents, ReferenceType refType, String refId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive");
        }
        if (refType == null) {
            throw new IllegalArgumentException("referenceType must not be null");
        }
        if (refId == null || refId.isBlank()) {
            throw new IllegalArgumentException("referenceId must not be blank");
        }
        return refId.trim();
    }

    private WalletTransactionResponse resolveIdempotentMatch(
        WalletTransaction existing,
        UUID userId,
        Type expectedType,
        long amountCents,
        ReferenceType refType,
        String refId
    ) {
        UUID ownerId = existing.getWallet() != null ? existing.getWallet().getUserId() : null;
        if (ownerId != null && ownerId.equals(userId)
            && existing.getType() == expectedType
            && existing.getAmountCents() == amountCents) {
            log.info("Idempotent wallet {} replay detected user={} ref={}/{}", expectedType, userId, refType, refId);
            return toResponse(existing);
        }
        throw new DuplicateTransactionException(
            "Transaction already exists with mismatched parameters for ref=" + refType + "/" + refId);
    }

    @Transactional(readOnly = true)
    public Page<WalletTransactionResponse> getTransactions(UUID userId, Pageable pageable) {
        Wallet wallet = walletRepository.findByUserId(userId)
            .orElseThrow(() -> new WalletNotFoundException("Wallet not found for user: " + userId));
        return transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable)
            .map(this::toResponse);
    }

    private Wallet findOrCreateWallet(UUID userId) {
        return walletRepository.findByUserIdForUpdate(userId)
            .orElseGet(() -> createWallet(userId));
    }

    private Wallet createWallet(UUID userId) {
        Wallet wallet = new Wallet();
        wallet.setUserId(userId);
        return walletRepository.save(wallet);
    }

    private WalletTransaction recordTransaction(Wallet wallet, Type type, long amountCents,
                                                 ReferenceType refType, String refId, String description) {
        WalletTransaction txn = new WalletTransaction();
        txn.setWallet(wallet);
        txn.setType(type);
        txn.setAmountCents(amountCents);
        txn.setBalanceAfterCents(wallet.getBalanceCents());
        txn.setReferenceType(refType);
        txn.setReferenceId(refId);
        txn.setDescription(description);
        try {
            return transactionRepository.saveAndFlush(txn);
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateTransactionException(
                "Transaction already exists for ref=" + refType + "/" + refId);
        }
    }

    private WalletTransactionResponse toResponse(WalletTransaction txn) {
        return new WalletTransactionResponse(
            txn.getType().name(),
            txn.getAmountCents(),
            txn.getBalanceAfterCents(),
            txn.getReferenceType().name(),
            txn.getReferenceId(),
            txn.getDescription(),
            txn.getCreatedAt()
        );
    }

    private void recordLedgerEntry(UUID walletId, UUID userId, ReferenceType refType,
                                   long amountCents, String refId, boolean isCredit) {
        walletLedgerService.recordDoubleEntry(walletId, userId, refType, amountCents, refId, isCredit);
    }
}
