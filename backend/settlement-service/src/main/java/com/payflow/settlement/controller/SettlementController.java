package com.payflow.settlement.controller;

import com.payflow.common.dto.ApiResponse;
import com.payflow.settlement.dto.PayoutResponse;
import com.payflow.settlement.dto.SettlementBatchResponse;
import com.payflow.settlement.model.Payout;
import com.payflow.settlement.model.SettlementBatch;
import com.payflow.settlement.repository.PayoutRepository;
import com.payflow.settlement.repository.SettlementBatchRepository;
import com.payflow.settlement.service.SettlementScheduler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/v1/settlements")
@Tag(name = "Settlement", description = "Settlement batch processing and payout management")
public class SettlementController {

    private final SettlementScheduler settlementScheduler;
    private final SettlementBatchRepository batchRepository;
    private final PayoutRepository payoutRepository;

    public SettlementController(SettlementScheduler settlementScheduler,
                                SettlementBatchRepository batchRepository,
                                PayoutRepository payoutRepository) {
        this.settlementScheduler = settlementScheduler;
        this.batchRepository = batchRepository;
        this.payoutRepository = payoutRepository;
    }

    @PostMapping("/trigger")
    @Operation(summary = "Trigger settlement batch manually")
    public ResponseEntity<ApiResponse<SettlementBatchResponse>> triggerSettlement(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settlementDate) {

        LocalDate date = (settlementDate != null) ? settlementDate : LocalDate.now().minusDays(1);
        log.info("Manual settlement trigger for date: {}", date);

        SettlementBatch batch = settlementScheduler.triggerSettlement(date);
        SettlementBatchResponse response = mapBatchToResponse(batch);

        return ResponseEntity.ok(ApiResponse.<SettlementBatchResponse>builder()
                .success(true)
                .data(response)
                .build());
    }

    @GetMapping
    @Operation(summary = "List all settlement batches")
    public ResponseEntity<ApiResponse<List<SettlementBatchResponse>>> listBatches() {
        List<SettlementBatch> batches = batchRepository.findAll();
        List<SettlementBatchResponse> responses = batches.stream()
                .map(this::mapBatchToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.<List<SettlementBatchResponse>>builder()
                .success(true)
                .data(responses)
                .build());
    }

    @GetMapping("/{batchId}")
    @Operation(summary = "Get settlement batch by ID")
    public ResponseEntity<ApiResponse<SettlementBatchResponse>> getBatchById(@PathVariable String batchId) {
        SettlementBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new RuntimeException("Batch not found: " + batchId));

        return ResponseEntity.ok(ApiResponse.<SettlementBatchResponse>builder()
                .success(true)
                .data(mapBatchToResponse(batch))
                .build());
    }

    @GetMapping("/payouts")
    @Operation(summary = "List payouts, optionally filtered by merchant")
    public ResponseEntity<ApiResponse<List<PayoutResponse>>> listPayouts(
            @RequestParam(required = false) String merchantId) {

        List<Payout> payouts = (merchantId != null)
                ? payoutRepository.findByMerchantId(merchantId)
                : payoutRepository.findAll();

        List<PayoutResponse> responses = payouts.stream()
                .map(this::mapPayoutToResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.<List<PayoutResponse>>builder()
                .success(true)
                .data(responses)
                .build());
    }

    private SettlementBatchResponse mapBatchToResponse(SettlementBatch batch) {
        return SettlementBatchResponse.builder()
                .id(batch.getId())
                .settlementDate(batch.getSettlementDate())
                .totalGross(batch.getTotalGross())
                .totalRefunds(batch.getTotalRefunds())
                .totalMdr(batch.getTotalMdr())
                .totalGst(batch.getTotalGst())
                .totalNet(batch.getTotalNet())
                .recordCount(batch.getRecordCount())
                .status(batch.getStatus().name())
                .createdAt(batch.getCreatedAt())
                .build();
    }

    private PayoutResponse mapPayoutToResponse(Payout payout) {
        return PayoutResponse.builder()
                .id(payout.getId())
                .merchantId(payout.getMerchantId())
                .settlementRecordId(payout.getSettlementRecordId())
                .amount(payout.getAmount())
                .bankAccountNumber(payout.getBankAccountNumber())
                .bankIfsc(payout.getBankIfsc())
                .status(payout.getStatus().name())
                .createdAt(payout.getCreatedAt())
                .build();
    }
}
