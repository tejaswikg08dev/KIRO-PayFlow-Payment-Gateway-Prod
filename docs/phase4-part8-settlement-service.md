# Phase 4 Part 8: Settlement Service Implementation

## Overview

The Settlement Service runs daily batch processing to calculate merchant payouts. It aggregates captured transactions, applies MDR fees and GST, and initiates bank transfers to merchant accounts. Built with Spring Batch for reliable, restartable job execution.

## Settlement Flow

```
┌──────────────────────────────────────────────────────────────────┐
│                   DAILY SETTLEMENT PROCESS                         │
│                   (Runs at 2:00 AM IST)                           │
└──────────────────────────────────────────────────────────────────┘

Step 1: FETCH         Step 2: CALCULATE      Step 3: PAYOUT
┌──────────────┐     ┌──────────────┐      ┌──────────────┐
│ Query all    │     │ For each txn:│      │ Group by     │
│ CAPTURED     │────▶│ - Apply MDR  │─────▶│ merchant     │
│ transactions │     │ - Calculate  │      │ - Sum net    │
│ (yesterday)  │     │   GST on MDR │      │ - Create     │
│              │     │ - Net amount │      │   payout     │
└──────────────┘     └──────────────┘      └──────────────┘
```

## Spring Batch Configuration

```java
@Configuration
@EnableBatchProcessing
@RequiredArgsConstructor
public class SettlementBatchConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final TransactionItemReader transactionReader;
    private final FeeCalculationProcessor feeProcessor;
    private final SettlementRecordWriter recordWriter;
    private final PayoutWriter payoutWriter;

    @Bean
    public Job dailySettlementJob() {
        return new JobBuilder("dailySettlementJob", jobRepository)
            .incrementer(new RunIdIncrementer())
            .listener(new SettlementJobListener())
            .start(createBatchStep())
            .next(calculateFeesStep())
            .next(initiatePayoutsStep())
            .next(updateStatusStep())
            .build();
    }

    @Bean
    public Step createBatchStep() {
        return new StepBuilder("createBatch", jobRepository)
            .tasklet((contribution, chunkContext) -> {
                LocalDate settlementDate = LocalDate.now().minusDays(1);
                // Create settlement batch record
                SettlementBatch batch = SettlementBatch.builder()
                    .batchDate(settlementDate)
                    .status(BatchStatus.PROCESSING)
                    .startedAt(LocalDateTime.now())
                    .build();
                batchRepository.save(batch);
                // Store batch ID in execution context
                chunkContext.getStepContext().getStepExecution()
                    .getJobExecution().getExecutionContext()
                    .put("batchId", batch.getId().toString());
                return RepeatStatus.FINISHED;
            }, transactionManager)
            .build();
    }

    @Bean
    public Step calculateFeesStep() {
        return new StepBuilder("calculateFees", jobRepository)
            .<CapturedTransaction, SettlementRecord>chunk(100,
                transactionManager)
            .reader(transactionReader)
            .processor(feeProcessor)
            .writer(recordWriter)
            .faultTolerant()
            .skipLimit(10)
            .skip(FeeCalculationException.class)
            .retryLimit(3)
            .retry(DatabaseAccessException.class)
            .build();
    }

    @Bean
    public Step initiatePayoutsStep() {
        return new StepBuilder("initiatePayouts", jobRepository)
            .<MerchantPayout, Payout>chunk(50, transactionManager)
            .reader(merchantPayoutReader())
            .processor(payoutProcessor())
            .writer(payoutWriter)
            .build();
    }
}
```

## Fee Calculation Processor

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class FeeCalculationProcessor
        implements ItemProcessor<CapturedTransaction, SettlementRecord> {

    private final MerchantServiceClient merchantClient;

    /**
     * Fee Calculation Formula:
     *
     * MDR Amount = Transaction Amount × MDR%
     * GST Amount = MDR Amount × GST% (18%)
     * Total Fee = MDR Amount + GST Amount
     * Net Amount = Transaction Amount - Total Fee
     *
     * Example (₹1000 card payment, MDR 2%):
     *   MDR = ₹1000 × 0.02 = ₹20.00
     *   GST = ₹20 × 0.18 = ₹3.60
     *   Total Fee = ₹23.60
     *   Net to Merchant = ₹976.40
     */
    @Override
    public SettlementRecord process(CapturedTransaction txn) {
        // Get fee config for this merchant + payment method
        FeeConfigResponse feeConfig = merchantClient.getMerchantFees(
            UUID.fromString(txn.getMerchantId()),
            txn.getPaymentMethod()
        ).stream().findFirst()
            .orElseThrow(() -> new FeeCalculationException(
                "No fee config for merchant: " + txn.getMerchantId()));

        long grossAmount = txn.getAmount();

        // Calculate MDR
        BigDecimal mdrPercent = feeConfig.mdrPercent();
        long mdrAmount = BigDecimal.valueOf(grossAmount)
            .multiply(mdrPercent)
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .longValue();

        // Add fixed fee if applicable
        long fixedFee = feeConfig.fixedFee()
            .multiply(BigDecimal.valueOf(100))  // Convert to paise
            .longValue();
        mdrAmount += fixedFee;

        // Calculate GST on MDR
        BigDecimal gstPercent = feeConfig.gstPercent();
        long gstAmount = BigDecimal.valueOf(mdrAmount)
            .multiply(gstPercent)
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
            .longValue();

        // Net amount
        long totalFee = mdrAmount + gstAmount;
        long netAmount = grossAmount - totalFee;

        log.debug("Settlement calc: gross={}, mdr={}, gst={}, net={}",
            grossAmount, mdrAmount, gstAmount, netAmount);

        return SettlementRecord.builder()
            .merchantId(UUID.fromString(txn.getMerchantId()))
            .transactionId(UUID.fromString(txn.getTransactionId()))
            .grossAmount(grossAmount)
            .mdrAmount(mdrAmount)
            .gstAmount(gstAmount)
            .netAmount(netAmount)
            .createdAt(LocalDateTime.now())
            .build();
    }
}
```

## Payout Service

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutService {

    private final PayoutRepository payoutRepository;
    private final SettlementRecordRepository recordRepository;
    private final MerchantServiceClient merchantClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Aggregates settlement records per merchant and creates payout
     */
    public List<Payout> createPayouts(UUID batchId) {
        // Group settlement records by merchant
        Map<UUID, Long> merchantTotals = recordRepository
            .findByBatchId(batchId).stream()
            .collect(Collectors.groupingBy(
                SettlementRecord::getMerchantId,
                Collectors.summingLong(SettlementRecord::getNetAmount)
            ));

        List<Payout> payouts = new ArrayList<>();

        for (Map.Entry<UUID, Long> entry : merchantTotals.entrySet()) {
            UUID merchantId = entry.getKey();
            Long totalAmount = entry.getValue();

            // Minimum payout threshold: ₹100
            if (totalAmount < 100_00L) {
                log.info("Skipping payout for merchant {}: below minimum " +
                    "(amount: {})", merchantId, totalAmount);
                continue;
            }

            // Get merchant bank details
            MerchantResponse merchant = merchantClient
                .getMerchant(merchantId);

            Payout payout = Payout.builder()
                .merchantId(merchantId)
                .batchId(batchId)
                .amount(totalAmount)
                .accountNumber(merchant.settlementAccountNumber())
                .ifscCode(merchant.settlementIfsc())
                .status(PayoutStatus.INITIATED)
                .initiatedAt(LocalDateTime.now())
                .build();

            payout = payoutRepository.save(payout);

            // In production: call bank API to initiate NEFT/IMPS
            // For simulator: auto-complete after delay
            simulatePayoutCompletion(payout);

            payouts.add(payout);
        }

        // Publish settlement event
        kafkaTemplate.send("settlement.completed",
            batchId.toString(),
            new SettlementCompletedEvent(batchId, payouts.size()));

        return payouts;
    }

    private void simulatePayoutCompletion(Payout payout) {
        // Simulate bank UTR generation
        String utr = "PAYFLOW" + LocalDate.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd"))
            + String.format("%06d",
                ThreadLocalRandom.current().nextInt(999999));

        payout.setUtr(utr);
        payout.setStatus(PayoutStatus.COMPLETED);
        payout.setCompletedAt(LocalDateTime.now());
        payoutRepository.save(payout);

        log.info("Payout completed: merchant={}, amount={}, utr={}",
            payout.getMerchantId(), payout.getAmount(), utr);
    }
}
```

## Scheduler

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class SettlementScheduler {

    private final JobLauncher jobLauncher;
    private final Job dailySettlementJob;

    /**
     * Runs daily at 2:00 AM IST
     * Processes all captured transactions from the previous day
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Kolkata")
    public void runDailySettlement() {
        log.info("Starting daily settlement job");

        try {
            JobParameters params = new JobParametersBuilder()
                .addLocalDate("settlementDate", LocalDate.now().minusDays(1))
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

            JobExecution execution = jobLauncher.run(
                dailySettlementJob, params);

            log.info("Settlement job completed with status: {}",
                execution.getStatus());

        } catch (Exception e) {
            log.error("Settlement job failed", e);
            // Alert ops team
        }
    }

    /**
     * Manual trigger for specific date (admin endpoint)
     */
    public void runSettlementForDate(LocalDate date) {
        JobParameters params = new JobParametersBuilder()
            .addLocalDate("settlementDate", date)
            .addLong("timestamp", System.currentTimeMillis())
            .toJobParameters();

        jobLauncher.run(dailySettlementJob, params);
    }
}
```

## Settlement Report Example

```
┌──────────────────────────────────────────────────────────────────┐
│              SETTLEMENT REPORT - 2024-01-15                        │
├──────────────────────────────────────────────────────────────────┤
│ Batch ID: b7f3e2a1-...                                           │
│ Status: COMPLETED                                                 │
│ Processing Time: 45 seconds                                       │
├──────────────────────────────────────────────────────────────────┤
│                                                                    │
│ Total Transactions: 1,247                                         │
│ Total Gross Amount: ₹8,34,500.00                                  │
│ Total MDR: ₹16,690.00                                             │
│ Total GST: ₹3,004.20                                              │
│ Total Net Payout: ₹8,14,805.80                                    │
│                                                                    │
│ Merchants Paid: 23                                                │
│ Skipped (below minimum): 2                                        │
│                                                                    │
├──────────────┬──────────┬────────┬────────┬──────────────────────┤
│ Merchant     │ Gross    │ MDR    │ GST    │ Net Payout           │
├──────────────┼──────────┼────────┼────────┼──────────────────────┤
│ ShopEasy     │ ₹2,50,000│ ₹5,000 │ ₹900   │ ₹2,44,100           │
│ TechMart     │ ₹1,80,000│ ₹3,600 │ ₹648   │ ₹1,75,752           │
│ FoodHub      │ ₹95,000  │ ₹1,900 │ ₹342   │ ₹92,758             │
│ ...          │ ...      │ ...    │ ...    │ ...                  │
└──────────────┴──────────┴────────┴────────┴──────────────────────┘
```

## Database Schema

```sql
-- V1__create_settlement_tables.sql
CREATE TABLE settlement_batches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_date DATE NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_gross_amount BIGINT DEFAULT 0,
    total_fees BIGINT DEFAULT 0,
    total_net_amount BIGINT DEFAULT 0,
    transaction_count INT DEFAULT 0,
    started_at TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE TABLE settlement_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    batch_id UUID NOT NULL REFERENCES settlement_batches(id),
    merchant_id UUID NOT NULL,
    transaction_id UUID NOT NULL,
    gross_amount BIGINT NOT NULL,
    mdr_amount BIGINT NOT NULL,
    gst_amount BIGINT NOT NULL,
    net_amount BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_settlement_records_batch ON settlement_records(batch_id);
CREATE INDEX idx_settlement_records_merchant ON settlement_records(merchant_id);

CREATE TABLE payouts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    merchant_id UUID NOT NULL,
    batch_id UUID REFERENCES settlement_batches(id),
    amount BIGINT NOT NULL,
    account_number VARCHAR(20),
    ifsc_code VARCHAR(11),
    status VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
    utr VARCHAR(50),
    initiated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_payouts_merchant ON payouts(merchant_id);
CREATE INDEX idx_payouts_status ON payouts(status);
```
