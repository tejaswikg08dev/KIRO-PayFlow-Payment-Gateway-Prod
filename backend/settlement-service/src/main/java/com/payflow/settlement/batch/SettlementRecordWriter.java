package com.payflow.settlement.batch;

import com.payflow.common.util.IdGenerator;
import com.payflow.settlement.model.Payout;
import com.payflow.settlement.model.Payout.PayoutStatus;
import com.payflow.settlement.model.SettlementRecord;
import com.payflow.settlement.repository.PayoutRepository;
import com.payflow.settlement.repository.SettlementRecordRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

/**
 * ItemWriter that persists SettlementRecords to the database and creates corresponding Payouts.
 */
@Slf4j
@Component
public class SettlementRecordWriter implements ItemWriter<SettlementRecord> {

    private final SettlementRecordRepository settlementRecordRepository;
    private final PayoutRepository payoutRepository;

    public SettlementRecordWriter(SettlementRecordRepository settlementRecordRepository,
                                  PayoutRepository payoutRepository) {
        this.settlementRecordRepository = settlementRecordRepository;
        this.payoutRepository = payoutRepository;
    }

    @Override
    public void write(Chunk<? extends SettlementRecord> chunk) {
        for (SettlementRecord record : chunk) {
            // Save settlement record
            settlementRecordRepository.save(record);
            log.info("Saved settlement record: id={}, merchant={}, net={}",
                    record.getId(), record.getMerchantId(), record.getNetAmount());

            // Create payout entry
            Payout payout = Payout.builder()
                    .id(IdGenerator.generate())
                    .merchantId(record.getMerchantId())
                    .settlementRecordId(record.getId())
                    .amount(record.getNetAmount())
                    .bankAccountNumber("XXXX") // Will be fetched from merchant service
                    .bankIfsc("XXXX")
                    .status(PayoutStatus.PENDING)
                    .build();

            payoutRepository.save(payout);
            log.info("Created payout: id={}, merchant={}, amount={}",
                    payout.getId(), payout.getMerchantId(), payout.getAmount());
        }
    }
}
