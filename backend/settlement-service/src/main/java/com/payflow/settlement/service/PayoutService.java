package com.payflow.settlement.service;

import com.payflow.settlement.model.Payout;
import com.payflow.settlement.model.Payout.PayoutStatus;
import com.payflow.settlement.repository.PayoutRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Service to initiate bank transfers for payouts.
 * Currently uses a stub implementation — will integrate with actual bank APIs in production.
 */
@Slf4j
@Service
public class PayoutService {

    private final PayoutRepository payoutRepository;

    public PayoutService(PayoutRepository payoutRepository) {
        this.payoutRepository = payoutRepository;
    }

    /**
     * Initiate bank transfer for a payout.
     * Stub implementation: marks payout as PROCESSING, simulates transfer, marks as COMPLETED.
     */
    @Transactional
    public void initiatePayout(Payout payout) {
        log.info("Initiating bank transfer: payoutId={}, merchant={}, amount={}, account={}",
                payout.getId(), payout.getMerchantId(), payout.getAmount(), payout.getBankAccountNumber());

        payout.setStatus(PayoutStatus.PROCESSING);
        payoutRepository.save(payout);

        // Stub: simulate bank transfer
        try {
            simulateBankTransfer(payout);
            payout.setStatus(PayoutStatus.COMPLETED);
            log.info("Payout completed: payoutId={}", payout.getId());
        } catch (Exception e) {
            payout.setStatus(PayoutStatus.FAILED);
            log.error("Payout failed: payoutId={}, error={}", payout.getId(), e.getMessage());
        }

        payoutRepository.save(payout);
    }

    /**
     * Process all pending payouts.
     */
    @Transactional
    public void processAllPendingPayouts() {
        List<Payout> pendingPayouts = payoutRepository.findByStatus(PayoutStatus.PENDING);
        log.info("Processing {} pending payouts", pendingPayouts.size());

        for (Payout payout : pendingPayouts) {
            initiatePayout(payout);
        }
    }

    /**
     * Get payouts by merchant.
     */
    public List<Payout> getPayoutsByMerchant(String merchantId) {
        return payoutRepository.findByMerchantId(merchantId);
    }

    /**
     * Stub: Simulates a bank transfer with a slight delay.
     */
    private void simulateBankTransfer(Payout payout) {
        try {
            Thread.sleep(100); // Simulate network latency
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Bank transfer interrupted", e);
        }
        log.debug("Bank transfer simulation completed for payout: {}", payout.getId());
    }
}
