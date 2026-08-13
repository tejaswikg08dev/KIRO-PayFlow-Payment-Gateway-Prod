package com.payflow.settlement.service;

import com.payflow.common.util.IdGenerator;
import com.payflow.settlement.batch.CapturedPaymentReader;
import com.payflow.settlement.model.SettlementBatch;
import com.payflow.settlement.model.SettlementBatch.BatchStatus;
import com.payflow.settlement.model.SettlementRecord;
import com.payflow.settlement.repository.SettlementBatchRepository;
import com.payflow.settlement.repository.SettlementRecordRepository;
import com.payflow.settlement.kafka.SettlementEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Scheduler that triggers the daily settlement batch job at midnight.
 */
@Slf4j
@Service
public class SettlementScheduler {

    private final JobLauncher jobLauncher;
    private final Job settlementJob;
    private final SettlementBatchRepository batchRepository;
    private final SettlementRecordRepository recordRepository;
    private final SettlementEventPublisher eventPublisher;
    private final CapturedPaymentReader capturedPaymentReader;
    private final PayoutService payoutService;

    public SettlementScheduler(JobLauncher jobLauncher,
                               Job settlementJob,
                               SettlementBatchRepository batchRepository,
                               SettlementRecordRepository recordRepository,
                               SettlementEventPublisher eventPublisher,
                               CapturedPaymentReader capturedPaymentReader,
                               PayoutService payoutService) {
        this.jobLauncher = jobLauncher;
        this.settlementJob = settlementJob;
        this.batchRepository = batchRepository;
        this.recordRepository = recordRepository;
        this.eventPublisher = eventPublisher;
        this.capturedPaymentReader = capturedPaymentReader;
        this.payoutService = payoutService;
    }

    /**
     * Runs daily at midnight to settle the previous day's captured payments.
     */
    @Scheduled(cron = "0 0 0 * * ?")
    public void runDailySettlement() {
        triggerSettlement(LocalDate.now().minusDays(1));
    }

    /**
     * Trigger settlement for a specific date. Can also be called manually via controller.
     */
    public SettlementBatch triggerSettlement(LocalDate settlementDate) {
        log.info("Triggering settlement batch for date: {}", settlementDate);

        // Create batch record
        SettlementBatch batch = SettlementBatch.builder()
                .id(IdGenerator.generateSettlementId())
                .settlementDate(settlementDate)
                .status(BatchStatus.PENDING)
                .totalGross(BigDecimal.ZERO)
                .totalRefunds(BigDecimal.ZERO)
                .totalMdr(BigDecimal.ZERO)
                .totalGst(BigDecimal.ZERO)
                .totalNet(BigDecimal.ZERO)
                .recordCount(0)
                .build();
        batchRepository.save(batch);

        try {
            // Update status to PROCESSING
            batch.setStatus(BatchStatus.PROCESSING);
            batchRepository.save(batch);

            // Reset reader for new run
            capturedPaymentReader.reset();

            // Launch Spring Batch job
            JobParameters params = new JobParametersBuilder()
                    .addString("batchId", batch.getId())
                    .addString("settlementDate", settlementDate.toString())
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            jobLauncher.run(settlementJob, params);

            // Aggregate totals from records
            List<SettlementRecord> records = recordRepository.findByBatchId(batch.getId());
            updateBatchTotals(batch, records);

            batch.setStatus(BatchStatus.COMPLETED);
            batchRepository.save(batch);

            // Publish settlement completed event
            eventPublisher.publishSettlementCompleted(batch);

            // Process payouts
            payoutService.processAllPendingPayouts();

            log.info("Settlement batch completed: id={}, records={}, net={}",
                    batch.getId(), batch.getRecordCount(), batch.getTotalNet());

        } catch (Exception e) {
            log.error("Settlement batch failed: id={}, error={}", batch.getId(), e.getMessage(), e);
            batch.setStatus(BatchStatus.FAILED);
            batchRepository.save(batch);
        }

        return batch;
    }

    private void updateBatchTotals(SettlementBatch batch, List<SettlementRecord> records) {
        BigDecimal totalGross = BigDecimal.ZERO;
        BigDecimal totalRefunds = BigDecimal.ZERO;
        BigDecimal totalMdr = BigDecimal.ZERO;
        BigDecimal totalGst = BigDecimal.ZERO;
        BigDecimal totalNet = BigDecimal.ZERO;

        for (SettlementRecord record : records) {
            totalGross = totalGross.add(record.getGrossAmount());
            totalRefunds = totalRefunds.add(record.getRefundAmount());
            totalMdr = totalMdr.add(record.getMdrAmount());
            totalGst = totalGst.add(record.getGstAmount());
            totalNet = totalNet.add(record.getNetAmount());
        }

        batch.setTotalGross(totalGross);
        batch.setTotalRefunds(totalRefunds);
        batch.setTotalMdr(totalMdr);
        batch.setTotalGst(totalGst);
        batch.setTotalNet(totalNet);
        batch.setRecordCount(records.size());
    }
}
