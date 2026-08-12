package com.payflow.settlement.batch;

import com.payflow.settlement.model.SettlementRecord;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

/**
 * Spring Batch Job configuration for daily settlement processing.
 * Single step: read captured payments → calculate fees → write settlement records.
 */
@Configuration
public class SettlementJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final CapturedPaymentReader capturedPaymentReader;
    private final FeeCalculationProcessor feeCalculationProcessor;
    private final SettlementRecordWriter settlementRecordWriter;

    public SettlementJobConfig(JobRepository jobRepository,
                               PlatformTransactionManager transactionManager,
                               CapturedPaymentReader capturedPaymentReader,
                               FeeCalculationProcessor feeCalculationProcessor,
                               SettlementRecordWriter settlementRecordWriter) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.capturedPaymentReader = capturedPaymentReader;
        this.feeCalculationProcessor = feeCalculationProcessor;
        this.settlementRecordWriter = settlementRecordWriter;
    }

    @Bean
    public Job settlementJob() {
        return new JobBuilder("settlementJob", jobRepository)
                .start(settlementStep())
                .build();
    }

    @Bean
    public Step settlementStep() {
        return new StepBuilder("settlementStep", jobRepository)
                .<Map<String, Object>, SettlementRecord>chunk(100, transactionManager)
                .reader(capturedPaymentReader)
                .processor(feeCalculationProcessor)
                .writer(settlementRecordWriter)
                .build();
    }
}
