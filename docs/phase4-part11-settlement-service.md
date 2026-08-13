# Phase 4 · Part 11 — Settlement Service

| Field | Value |
|-------|-------|
| **Project** | PayFlow Payment Gateway |
| **Phase** | 4 — Financial Processing |
| **Part** | 11 — Settlement Service (Batch Processing) |
| **Previous** | [Part 10 — Bank Simulator](./phase4-part10-bank-simulator.md) |
| **Next** | [Part 12 — Webhook Service](./phase4-part12-webhook-service.md) |
| **Time** | ~3 hours |
| **Difficulty** | ★★★★☆ (Advanced) |
| **Prerequisites** | Spring Batch basics, SQL, Payment lifecycle (Part 4) |
| **What You'll Build** | End-of-day batch settlement with fee calculation and payout generation |
| **Git Commit** | `feat(settlement): add batch settlement with fee calculation and payouts` |

---

## Table of Contents

1. [What is Settlement?](#1-what-is-settlement)
2. [Spring Batch Concepts](#2-spring-batch-concepts)
3. [SettlementJobConfig](#3-settlementjobconfig)
4. [CapturedPaymentReader](#4-capturedpaymentreader)
5. [FeeCalculationProcessor](#5-feecalculationprocessor)
6. [SettlementRecordWriter](#6-settlementrecordwriter)
7. [FeeCalculationService — The Math](#7-feecalculationservice)
8. [SettlementScheduler](#8-settlementscheduler)
9. [SettlementController](#9-settlementcontroller)
10. [Entities](#10-entities)
11. [What You Learned](#11-what-you-learned)
12. [Common Errors & Fixes](#12-common-errors--fixes)
13. [Git Commit](#13-git-commit)

---

## What You'll Learn

- What settlement means in payment processing (T+1, T+2 explained)
- How Spring Batch processes millions of records reliably with restart/retry
- How fee calculation works (MDR, GST, net payout)
- How to build a Reader → Processor → Writer pipeline for batch jobs
- How to schedule batch jobs with cron and trigger them manually
- How settlement entities relate to payments and payouts

---

## 1. What is Settlement?

Settlement is the **end-of-day money movement** from the payment gateway to the merchant's bank account.

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        PAYMENT LIFECYCLE                                  │
├──────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│  Day 1 (Transaction Day):                                                │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐          │
│  │ INITIATED│───→│AUTHORIZED│───→│ CAPTURED │───→│  SETTLED │          │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘          │
│    Customer       Bank says       Merchant says    Money moved           │
│    clicks pay     "funds OK"      "ship goods"     to merchant           │
│                                                                           │
│  Day 2 (Settlement Day — T+1):                                           │
│  ┌─────────────────────────────────────────────────────────────────┐    │
│  │  Settlement Batch Job (runs at midnight)                         │    │
│  │                                                                   │    │
│  │  1. Fetch all CAPTURED payments from yesterday                    │    │
│  │  2. Group by merchant                                             │    │
│  │  3. Calculate fees:                                               │    │
│  │     Gross Amount     = ₹1,00,000                                  │    │
│  │     - Refunds        = ₹2,000                                     │    │
│  │     - MDR (2%)       = ₹1,960  (2% of ₹98,000)                   │    │
│  │     - GST (18% MDR)  = ₹352.80 (18% of ₹1,960)                   │    │
│  │     ─────────────────────────────                                 │    │
│  │     Net Payout       = ₹95,687.20                                 │    │
│  │                                                                   │    │
│  │  4. Create Payout record (pending bank transfer)                  │    │
│  │  5. Mark payments as SETTLED                                      │    │
│  └─────────────────────────────────────────────────────────────────┘    │
│                                                                           │
└──────────────────────────────────────────────────────────────────────────┘
```

### T+1 Explained

| Term | Meaning | Example |
|------|---------|---------|
| T | Transaction day | Customer pays on Monday |
| T+1 | Next business day | Merchant receives money on Tuesday |
| T+2 | Two business days | Some banks settle on Wednesday |
| T+0 | Same day (rare) | Premium merchants get instant payout |

---

## 2. Spring Batch Concepts

```
┌──────────────────────────────────────────────────────────────────┐
│                    SPRING BATCH ARCHITECTURE                      │
├──────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌─────────┐                                                     │
│  │   Job   │  ← Top-level container (SettlementJob)              │
│  └────┬────┘                                                     │
│       │                                                           │
│       ▼                                                           │
│  ┌─────────┐                                                     │
│  │  Step   │  ← One unit of work (processSettlements)            │
│  └────┬────┘                                                     │
│       │                                                           │
│       ├──→ ┌──────────┐  Read a "chunk" of items (100 payments)  │
│       │    │  Reader   │  (CapturedPaymentReader)                 │
│       │    └──────────┘                                           │
│       │                                                           │
│       ├──→ ┌───────────┐  Transform each item (calculate fees)   │
│       │    │ Processor  │  (FeeCalculationProcessor)              │
│       │    └───────────┘                                          │
│       │                                                           │
│       └──→ ┌──────────┐  Write the chunk (save to DB)            │
│            │  Writer   │  (SettlementRecordWriter)                │
│            └──────────┘                                           │
│                                                                   │
│  Chunk Processing (size=100):                                    │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │ Read 100 → Process 100 → Write 100 → Commit Transaction  │  │
│  │ Read 100 → Process 100 → Write 100 → Commit Transaction  │  │
│  │ Read 50  → Process 50  → Write 50  → Commit Transaction  │  │
│  │ Read 0   → Job Complete ✅                                  │  │
│  └────────────────────────────────────────────────────────────┘  │
│                                                                   │
│  WHY chunks: If job fails after processing 500 records,          │
│  only the current chunk (100) needs retry. The previous          │
│  400 are already committed. ← This is Spring Batch's superpower │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## 3. SettlementJobConfig

```java
package com.payflow.settlement.config;

import com.payflow.settlement.batch.*;
import com.payflow.settlement.model.CapturedPayment;
import com.payflow.settlement.model.SettlementRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@RequiredArgsConstructor
public class SettlementJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CapturedPaymentReader reader;
    private final FeeCalculationProcessor processor;
    private final SettlementRecordWriter writer;

    @Bean
    public Job settlementJob() {
        // WHY Job: Top-level orchestrator. Spring Batch tracks execution history
        // (start time, end time, status) in its metadata tables.
        // If it fails, you can restart from where it left off!
        return new JobBuilder("settlementJob", jobRepository)
                .start(processSettlementsStep())
                // WHY: Could add more steps: .next(generateReportsStep())
                .build();
    }

    @Bean
    public Step processSettlementsStep() {
        // WHY Step with chunk: Processes payments in chunks of 100
        // Each chunk = one database transaction
        // If chunk 5 fails, chunks 1-4 are already committed (safe)
        return new StepBuilder("processSettlements", jobRepository)
                .<CapturedPayment, SettlementRecord>chunk(100, transactionManager)
                // WHY 100: Balance between DB roundtrips and memory usage
                // Too small (10): Too many DB commits (slow)
                // Too large (10000): Uses too much memory + large rollback on failure
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .faultTolerant()
                // WHY skip: If one payment has bad data, skip it rather than stopping entire job
                .skipLimit(10)  // Allow up to 10 failures before stopping
                .skip(FeeCalculationException.class)
                // WHY retry: Transient DB errors should be retried
                .retryLimit(3)
                .retry(org.springframework.dao.TransientDataAccessException.class)
                .build();
    }
}
```

---

## 4. CapturedPaymentReader

```java
package com.payflow.settlement.batch;

import com.payflow.settlement.client.PaymentServiceClient;
import com.payflow.settlement.model.CapturedPayment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemReader;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;

/**
 * Reads CAPTURED payments from the Payment Service via Feign client.
 *
 * WHY Feign (not direct DB): Settlement runs as a separate microservice.
 * It doesn't have access to the payment DB directly (service boundary!).
 * Feign calls the Payment Service's API.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CapturedPaymentReader implements ItemReader<CapturedPayment> {

    private final PaymentServiceClient paymentClient;

    // WHY Iterator: Spring Batch calls read() repeatedly until it returns null.
    // We fetch all captured payments once, then iterate through them.
    private Iterator<CapturedPayment> iterator;
    private boolean initialized = false;

    @Override
    public CapturedPayment read() {
        // WHY lazy init: Only fetch on first call (not during bean creation)
        if (!initialized) {
            initialize();
        }

        // WHY return null when done: Spring Batch convention — null = "no more items"
        if (iterator.hasNext()) {
            return iterator.next();
        }
        return null;
    }

    private void initialize() {
        // WHY yesterday: T+1 settlement — we process yesterday's captured payments
        LocalDate settlementDate = LocalDate.now().minusDays(1);

        log.info("📖 Fetching captured payments for settlement date: {}", settlementDate);

        // WHY: Feign call to payment-service
        // GET /internal/payments?status=CAPTURED&date=2024-01-15
        List<CapturedPayment> payments = paymentClient.getCapturedPayments(settlementDate);

        log.info("📖 Found {} captured payments to settle", payments.size());

        iterator = payments.iterator();
        initialized = true;
    }
}
```

### Feign Client

```java
package com.payflow.settlement.client;

import com.payflow.settlement.model.CapturedPayment;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

// WHY FeignClient: Declarative HTTP client — define interface, Spring implements it
@FeignClient(name = "payment-service", url = "${services.payment-service.url}")
public interface PaymentServiceClient {

    @GetMapping("/internal/payments")
    List<CapturedPayment> getCapturedPayments(
            @RequestParam("status") String status,
            @RequestParam("date") LocalDate date
    );

    // WHY: Convenience method with default status
    default List<CapturedPayment> getCapturedPayments(LocalDate date) {
        return getCapturedPayments("CAPTURED", date);
    }
}
```

---

## 5. FeeCalculationProcessor

```java
package com.payflow.settlement.batch;

import com.payflow.settlement.model.CapturedPayment;
import com.payflow.settlement.model.SettlementRecord;
import com.payflow.settlement.service.FeeCalculationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

/**
 * Transforms a CapturedPayment into a SettlementRecord by calculating fees.
 *
 * WHY separate from Reader/Writer:
 * - Reader: "What do I need to process?" (fetching data)
 * - Processor: "How do I transform it?" (business logic)
 * - Writer: "Where do I put the result?" (persistence)
 * Separation makes each piece independently testable.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class FeeCalculationProcessor implements ItemProcessor<CapturedPayment, SettlementRecord> {

    private final FeeCalculationService feeService;

    @Override
    public SettlementRecord process(CapturedPayment payment) {
        // WHY: return null to SKIP this item (e.g., if already settled)
        if (payment.isAlreadySettled()) {
            log.warn("⚠️ Payment {} already settled, skipping", payment.getPaymentId());
            return null;  // Spring Batch will skip this item
        }

        // Calculate all fees
        FeeBreakdown fees = feeService.calculate(
                payment.getGrossAmountPaise(),
                payment.getRefundAmountPaise(),
                payment.getMerchantMdrPercent()
        );

        // Build settlement record
        SettlementRecord record = SettlementRecord.builder()
                .paymentId(payment.getPaymentId())
                .merchantId(payment.getMerchantId())
                .grossAmount(payment.getGrossAmountPaise())
                .refundAmount(payment.getRefundAmountPaise())
                .netAmount(fees.getNetAmountPaise())
                .mdrAmount(fees.getMdrAmountPaise())
                .gstAmount(fees.getGstAmountPaise())
                .mdrPercent(payment.getMerchantMdrPercent())
                .currency(payment.getCurrency())
                .settlementDate(payment.getCapturedDate().plusDays(1))
                .status("PENDING_PAYOUT")
                .build();

        log.debug("💰 Processed: payment={}, gross={}, net={}",
                payment.getPaymentId(), payment.getGrossAmountPaise(), fees.getNetAmountPaise());

        return record;
    }
}
```

---

## 6. SettlementRecordWriter

```java
package com.payflow.settlement.batch;

import com.payflow.settlement.model.Payout;
import com.payflow.settlement.model.SettlementRecord;
import com.payflow.settlement.repository.PayoutRepository;
import com.payflow.settlement.repository.SettlementRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes SettlementRecords to the database and creates Payout entries.
 *
 * WHY: The writer receives a CHUNK (batch of 100 records).
 * We save all records, then aggregate by merchant to create payouts.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SettlementRecordWriter implements ItemWriter<SettlementRecord> {

    private final SettlementRecordRepository recordRepository;
    private final PayoutRepository payoutRepository;

    @Override
    public void write(Chunk<? extends SettlementRecord> chunk) {
        // Step 1: Save all settlement records in batch
        // WHY saveAll: One DB roundtrip for 100 records (vs 100 individual saves)
        recordRepository.saveAll(chunk.getItems());

        // Step 2: Group by merchant and create/update payout entries
        // WHY: Multiple payments to same merchant become ONE payout (bank transfer)
        Map<String, Long> merchantPayouts = chunk.getItems().stream()
                .collect(Collectors.groupingBy(
                        SettlementRecord::getMerchantId,
                        Collectors.summingLong(SettlementRecord::getNetAmount)
                ));

        // Step 3: Create or update payout records
        merchantPayouts.forEach((merchantId, totalNetPaise) -> {
            Payout payout = payoutRepository.findPendingByMerchantAndDate(
                    merchantId, chunk.getItems().get(0).getSettlementDate()
            ).orElseGet(() -> Payout.builder()
                    .merchantId(merchantId)
                    .settlementDate(chunk.getItems().get(0).getSettlementDate())
                    .status("PENDING")
                    .amountPaise(0L)
                    .build());

            // WHY addAndGet: Accumulate across multiple chunks for same merchant
            payout.setAmountPaise(payout.getAmountPaise() + totalNetPaise);
            payoutRepository.save(payout);
        });

        log.info("✅ Wrote {} settlement records, {} merchant payouts",
                chunk.size(), merchantPayouts.size());
    }
}
```

---

## 7. FeeCalculationService

The actual fee math with a detailed example.

```java
package com.payflow.settlement.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Calculates settlement fees.
 *
 * Fee Structure (Indian payment gateway standard):
 * ┌───────────────────────────────────────────────────────────┐
 * │  Gross Amount        = Total payment collected            │
 * │  - Refunds           = Money returned to customers        │
 * │  = Net Billable      = What we charge fees on             │
 * │  - MDR (2%)          = Merchant Discount Rate (our fee)   │
 * │  - GST (18% of MDR)  = Government tax on our fee         │
 * │  = Net Payout        = What merchant actually receives    │
 * └───────────────────────────────────────────────────────────┘
 */
@Service
@Slf4j
public class FeeCalculationService {

    // WHY 18%: India's GST rate on financial services
    private static final double GST_RATE = 0.18;

    public FeeBreakdown calculate(long grossPaise, long refundPaise, double mdrPercent) {
        // Step 1: Net billable = Gross - Refunds
        // WHY subtract refunds: We don't charge MDR on refunded transactions
        long netBillable = grossPaise - refundPaise;

        // Step 2: MDR = Net Billable × MDR%
        // WHY MDR: This is PayFlow's revenue — the fee we charge merchants
        long mdrPaise = Math.round(netBillable * (mdrPercent / 100.0));

        // Step 3: GST = MDR × 18%
        // WHY GST on MDR: Government taxes our fee (not the full payment amount)
        long gstPaise = Math.round(mdrPaise * GST_RATE);

        // Step 4: Net Payout = Net Billable - MDR - GST
        long netPayoutPaise = netBillable - mdrPaise - gstPaise;

        return FeeBreakdown.builder()
                .grossAmountPaise(grossPaise)
                .refundAmountPaise(refundPaise)
                .netBillablePaise(netBillable)
                .mdrAmountPaise(mdrPaise)
                .gstAmountPaise(gstPaise)
                .netAmountPaise(netPayoutPaise)
                .mdrPercent(mdrPercent)
                .gstPercent(GST_RATE * 100)
                .build();
    }
}
```

### Worked Example: ₹1,00,000 Gross Payment

```
┌────────────────────────────────────────────────────────────────┐
│  SETTLEMENT CALCULATION EXAMPLE                                │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Merchant: "ShopEasy" (MerchantID: M001)                       │
│  MDR Rate: 2.0%                                                │
│  Settlement Date: 2024-01-16 (T+1)                             │
│                                                                 │
│  Transactions on 2024-01-15:                                    │
│    Payment 1: ₹50,000 (CAPTURED)                               │
│    Payment 2: ₹30,000 (CAPTURED)                               │
│    Payment 3: ₹20,000 (CAPTURED)                               │
│    Refund 1:  -₹2,000 (REFUNDED from earlier payment)          │
│                                                                 │
│  ─────────────────────────────────────────────────             │
│  Gross Amount:           ₹1,00,000  (50K + 30K + 20K)         │
│  Refunds:               -₹2,000                                │
│  Net Billable:           ₹98,000                               │
│                                                                 │
│  MDR (2% of ₹98,000):  -₹1,960                                │
│  GST (18% of ₹1,960):  -₹352.80  (rounded to ₹353)           │
│  ─────────────────────────────────────────────────             │
│  NET PAYOUT:             ₹95,687   ← merchant receives this   │
│                                                                 │
│  PayFlow Revenue:        ₹1,960   (MDR)                        │
│  Government Gets:        ₹353     (GST)                        │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

### Unit Test for Fee Calculation

```java
@Test
void shouldCalculateFeesCorrectly() {
    // Given: ₹1,00,000 gross, ₹2,000 refunds, 2% MDR
    long gross = 10_000_000L;  // ₹1,00,000 in paise
    long refunds = 200_000L;   // ₹2,000 in paise
    double mdr = 2.0;

    // When
    FeeBreakdown result = feeService.calculate(gross, refunds, mdr);

    // Then
    assertThat(result.getNetBillablePaise()).isEqualTo(9_800_000L);  // ₹98,000
    assertThat(result.getMdrAmountPaise()).isEqualTo(196_000L);       // ₹1,960
    assertThat(result.getGstAmountPaise()).isEqualTo(35_280L);        // ₹352.80 → ₹353
    assertThat(result.getNetAmountPaise()).isEqualTo(9_568_720L);     // ₹95,687.20
}
```

---

## 8. SettlementScheduler

```java
package com.payflow.settlement.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Triggers the settlement job on schedule.
 *
 * WHY @Scheduled: Simple, reliable scheduling built into Spring.
 * For production with multiple instances, use ShedLock or Quartz
 * to prevent the job from running on all instances simultaneously.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SettlementScheduler {

    private final JobLauncher jobLauncher;
    private final Job settlementJob;

    // WHY midnight: Banks batch their settlements at end-of-day.
    // Running at 00:30 gives a buffer after midnight (in case of clock skew)
    @Scheduled(cron = "0 30 0 * * *")  // 00:30 every day
    public void runSettlement() {
        log.info("⏰ Scheduled settlement job starting at {}", LocalDateTime.now());
        launchJob();
    }

    public JobExecution launchJob() {
        try {
            // WHY JobParameters with timestamp: Spring Batch requires unique parameters
            // for each run. Using timestamp ensures each execution is unique.
            JobParameters params = new JobParametersBuilder()
                    .addLocalDateTime("executionTime", LocalDateTime.now())
                    .addString("triggeredBy", "scheduler")
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(settlementJob, params);

            log.info("✅ Settlement job completed: status={}, records={}",
                    execution.getStatus(),
                    execution.getStepExecutions().stream()
                            .mapToLong(StepExecution::getWriteCount)
                            .sum());

            return execution;

        } catch (JobExecutionAlreadyRunningException e) {
            // WHY: Prevent double-execution (if previous run is still processing)
            log.warn("⚠️ Settlement job already running. Skipping this trigger.");
            return null;
        } catch (Exception e) {
            log.error("❌ Settlement job failed", e);
            throw new RuntimeException("Settlement job failed", e);
        }
    }
}
```

---

## 9. SettlementController

```java
package com.payflow.settlement.controller;

import com.payflow.settlement.model.SettlementBatch;
import com.payflow.settlement.repository.SettlementBatchRepository;
import com.payflow.settlement.scheduler.SettlementScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobExecution;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/settlements")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementScheduler scheduler;
    private final SettlementBatchRepository batchRepository;

    /**
     * POST /v1/settlements/trigger — Manually trigger settlement
     *
     * WHY manual trigger: Operations team needs to re-run settlement
     * if the scheduled run failed or if they need to process a specific day.
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerSettlement() {
        JobExecution execution = scheduler.launchJob();

        if (execution == null) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "Settlement job is already running"
            ));
        }

        return ResponseEntity.ok(Map.of(
                "jobId", execution.getJobId(),
                "status", execution.getStatus().toString(),
                "startTime", execution.getStartTime().toString()
        ));
    }

    /**
     * GET /v1/settlements/batches — List all settlement batches
     *
     * WHY: Operations dashboard shows settlement history, status, amounts
     */
    @GetMapping("/batches")
    public ResponseEntity<List<SettlementBatch>> listBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<SettlementBatch> batches = batchRepository
                .findAllByOrderBySettlementDateDesc(page, size);

        return ResponseEntity.ok(batches);
    }

    /**
     * GET /v1/settlements/batches/{id} — Get batch details
     */
    @GetMapping("/batches/{batchId}")
    public ResponseEntity<SettlementBatch> getBatch(@PathVariable String batchId) {
        return batchRepository.findById(batchId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /v1/settlements/merchant/{merchantId} — Merchant's settlement history
     */
    @GetMapping("/merchant/{merchantId}")
    public ResponseEntity<List<SettlementBatch>> merchantSettlements(
            @PathVariable String merchantId) {
        return ResponseEntity.ok(
                batchRepository.findByMerchantId(merchantId));
    }
}
```

---

## 10. Entities

### SettlementBatch

```java
package com.payflow.settlement.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents one settlement run (one day's batch for one merchant).
 */
@Entity
@Table(name = "settlement_batches")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SettlementBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String merchantId;

    @Column(nullable = false)
    private LocalDate settlementDate;  // WHY LocalDate: Settlement is per-day

    private long grossAmountPaise;     // Total collected
    private long refundAmountPaise;    // Total refunded
    private long mdrAmountPaise;       // Our fee
    private long gstAmountPaise;       // Government tax
    private long netAmountPaise;       // Merchant receives

    private int transactionCount;      // How many payments in this batch
    private String currency;           // INR

    @Enumerated(EnumType.STRING)
    private SettlementStatus status;   // PROCESSING, COMPLETED, FAILED

    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}

public enum SettlementStatus {
    PROCESSING,  // Job is running
    COMPLETED,   // All records processed, payout created
    FAILED,      // Job failed (needs retry)
    PAID_OUT     // Money transferred to merchant's bank
}
```

### SettlementRecord

```java
package com.payflow.settlement.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

/**
 * One payment within a settlement batch.
 * Links a payment to its fee calculation.
 */
@Entity
@Table(name = "settlement_records")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class SettlementRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String paymentId;       // FK to payment-service

    @Column(nullable = false)
    private String merchantId;

    private long grossAmount;        // Original payment amount (paise)
    private long refundAmount;       // Refunds against this payment (paise)
    private long netAmount;          // After all deductions (paise)
    private long mdrAmount;          // MDR fee charged (paise)
    private long gstAmount;          // GST on MDR (paise)
    private double mdrPercent;       // MDR rate applied
    private String currency;

    private LocalDate settlementDate;
    private String status;           // PENDING_PAYOUT, PAID_OUT

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private SettlementBatch batch;
}
```

### Payout

```java
package com.payflow.settlement.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Represents a bank transfer to a merchant.
 * One payout = all settlements for one merchant on one day.
 */
@Entity
@Table(name = "payouts")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String merchantId;

    private long amountPaise;          // Total payout amount
    private String currency;           // INR

    private LocalDate settlementDate;  // Which day's settlements this covers

    @Enumerated(EnumType.STRING)
    private PayoutStatus status;       // PENDING, PROCESSING, COMPLETED, FAILED

    private String bankAccountId;      // Merchant's bank account
    private String utrNumber;          // Bank's unique transaction reference

    private LocalDateTime createdAt;
    private LocalDateTime processedAt;
}

public enum PayoutStatus {
    PENDING,     // Created by settlement job, waiting for bank transfer
    PROCESSING,  // Bank transfer initiated
    COMPLETED,   // Money reached merchant's account
    FAILED       // Bank transfer failed (retry needed)
}
```

---

## 11. What You Learned

| # | Topic | Key Takeaway |
|---|-------|-------------|
| 1 | Settlement concept | End-of-day money movement from gateway to merchant (T+1) |
| 2 | Spring Batch Job | Container for Steps, tracks execution history for restart |
| 3 | Chunk processing | Read-Process-Write in batches of 100 (commit per chunk) |
| 4 | ItemReader | Fetches data via Feign client (service boundary respected) |
| 5 | ItemProcessor | Business logic — fee calculation, returns null to skip |
| 6 | ItemWriter | Batch saves + aggregates payouts by merchant |
| 7 | Fee calculation | Gross - Refunds - MDR(2%) - GST(18% of MDR) = Net |
| 8 | Scheduling | @Scheduled(cron) at midnight, with ShedLock for HA |
| 9 | Manual trigger | REST endpoint for operations team to re-run settlement |
| 10 | Entity model | SettlementBatch → SettlementRecord → Payout chain |

---

## 12. Common Errors & Fixes

| Error | Cause | Fix |
|-------|-------|-----|
| `JobInstanceAlreadyCompleteException` | Same job parameters used twice | Add unique timestamp to JobParameters |
| `JobExecutionAlreadyRunningException` | Previous job still running | Wait or stop previous execution |
| Feign `ConnectException` | Payment service is down | Start payment-service or check URL config |
| `FeeCalculationException` | Negative amount or NaN | Add validation in processor |
| Settlement amount = 0 | No CAPTURED payments found for yesterday | Check payment statuses and dates |
| Payout duplicated | Writer runs twice (restart without idempotency) | Use `findPendingByMerchantAndDate` upsert pattern |
| GST rounding error | Floating point arithmetic | Use `Math.round()` on paise (integers) |
| Job stops at 10 errors | `skipLimit(10)` reached | Fix bad data or increase skip limit |

---

## 13. Git Commit

```bash
# Stage settlement service files
git add backend/settlement-service/

# Commit
git commit -m "feat(settlement): add batch settlement with fee calculation and payouts

- SettlementJobConfig: Spring Batch job with chunked processing (100)
- CapturedPaymentReader: Feign client to fetch CAPTURED payments
- FeeCalculationProcessor: MDR + GST calculation per payment
- SettlementRecordWriter: batch save + merchant payout aggregation
- FeeCalculationService: Gross - Refunds - MDR(2%) - GST(18%) = Net
- SettlementScheduler: @Scheduled cron at 00:30 daily
- SettlementController: manual trigger + batch listing
- Entities: SettlementBatch, SettlementRecord, Payout
- Fault tolerance: skipLimit(10), retryLimit(3)"

# Push
git push origin feature/phase4-settlement-service
```

---

## Document Index

| # | Document | Status |
|---|----------|--------|
| 01 | Project Overview & Architecture | ✅ |
| 02 | Development Environment Setup | ✅ |
| 03 | Merchant Service (CRUD + Auth) | ✅ |
| 04 | Payment Service (Core Processing) | ✅ |
| 05 | API Gateway (Routing + Security) | ✅ |
| 06 | Kafka Event Streaming | ✅ |
| 07 | Redis Caching & Idempotency | ✅ |
| 08 | Ledger Service (Double-Entry) | ✅ |
| 09a | ISO 8583 Message Parsing | ✅ |
| 09b | Netty TCP Client | ✅ |
| 09c | Fraud Detection & Smart Routing | ✅ |
| 10 | Bank Simulator | ✅ |
| **11** | **Settlement Service** | **📍 Current** |
| 12 | Webhook Service | 🔜 Next |
| 13 | Notification Service | ⬜ |
| 14 | Docker & Containerization | ⬜ |
| 15a | Frontend Setup | ⬜ |
| 15b | Frontend Features | ⬜ |

---

## Next Steps

In **Part 12**, we'll build the **Webhook Service** — real-time push notifications to merchants:
- Kafka consumer for payment events
- HTTP delivery with HMAC-SHA256 signatures
- Exponential backoff retry (5min → 30min → 2hr → 24hr)
- Dead letter topic for failed deliveries

---

[← Previous: Part 10 — Bank Simulator](./phase4-part10-bank-simulator.md) | [Next: Part 12 — Webhook Service →](./phase4-part12-webhook-service.md)
