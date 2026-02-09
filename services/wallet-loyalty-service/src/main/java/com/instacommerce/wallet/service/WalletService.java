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
        Wallet wallet = walletRepository.findByUserId(userId)
            .orElseGet(() -> createWallet(userId));
        return new WalletResponse(wallet.getBalanceCents(), wallet.getCurrency());
    }

    @Transactional
    @CacheEvict(value = "walletBalance", key = "#userId")
    public WalletTransactionResponse credit(UUID userId, long amountCents,
                                            ReferenceType refType, String refId, String description) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
            .orElseGet(() -> createWallet(userId));

        wallet.setBalanceCents(wallet.getBalanceCents() + amountCents);
        walletRepository.save(wallet);

        WalletTransaction txn = recordTransaction(wallet, Type.CREDIT, amountCents, refType, refId, description);
        recordLedgerEntry(wallet.getId(), userId, refType, amountCents, refId, true);
        log.info("Credited {} cents to wallet for user={} ref={}/{}", amountCents, userId, refType, refId);

        outboxService.publish("Wallet", wallet.getId().toString(), "WalletCredited", txn.getId());
        return toResponse(txn);
    }

    @Transactional
    @CacheEvict(value = "walletBalance", key = "#userId")
    public WalletTransactionResponse debit(UUID userId, long amountCents,
                                           ReferenceType refType, String refId, String description) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new WalletNotFoundException("Wallet not found for user: " + userId));

        if (wallet.getBalanceCents() < amountCents) {
            throw new InsufficientBalanceException(
                String.format("Insufficient balance: available=%d, requested=%d", wallet.getBalanceCents(), amountCents));
        }

        wallet.setBalanceCents(wallet.getBalanceCents() - amountCents);
        walletRepository.save(wallet);

        WalletTransaction txn = recordTransaction(wallet, Type.DEBIT, amountCents, refType, refId, description);
        recordLedgerEntry(wallet.getId(), userId, refType, amountCents, refId, false);
        log.info("Debited {} cents from wallet for user={} ref={}/{}", amountCents, userId, refType, refId);

        outboxService.publish("Wallet", wallet.getId().toString(), "WalletDebited", txn.getId());
        return toResponse(txn);
    }

    @Transactional(readOnly = true)
    public Page<WalletTransactionResponse> getTransactions(UUID userId, Pageable pageable) {
        Wallet wallet = walletRepository.findByUserId(userId)
            .orElseThrow(() -> new WalletNotFoundException("Wallet not found for user: " + userId));
        return transactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable)
            .map(this::toResponse);
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
            return transactionRepository.save(txn);
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
        switch (refType) {
            case TOPUP -> walletLedgerService.recordTopUp(walletId, userId, amountCents, refId);
            case ORDER -> walletLedgerService.recordPurchase(walletId, userId, amountCents, refId);
            case REFUND -> walletLedgerService.recordRefund(walletId, userId, amountCents, refId);
            case CASHBACK, REFERRAL -> walletLedgerService.recordPromotion(
                    walletId, userId, amountCents, refType.name(), refId);
        }
    }
}
