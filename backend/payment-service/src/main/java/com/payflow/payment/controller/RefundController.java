package com.payflow.payment.controller;

import com.payflow.common.dto.ApiResponse;
import com.payflow.payment.dto.RefundRequest;
import com.payflow.payment.dto.RefundResponse;
import com.payflow.payment.service.RefundService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for refund operations.
 */
@RestController
@RequestMapping("/v1/refunds")
@RequiredArgsConstructor
@Tag(name = "Refunds", description = "Refund management APIs")
public class RefundController {

    private final RefundService refundService;

    @PostMapping
    @Operation(summary = "Create a refund for a captured payment")
    public ResponseEntity<ApiResponse<RefundResponse>> createRefund(
            @Valid @RequestBody RefundRequest request) {
        RefundResponse response = refundService.createRefund(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/{refundId}")
    @Operation(summary = "Get refund by ID")
    public ResponseEntity<ApiResponse<RefundResponse>> getRefund(
            @PathVariable String refundId) {
        RefundResponse response = refundService.getRefund(refundId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping
    @Operation(summary = "List refunds for a payment")
    public ResponseEntity<ApiResponse<List<RefundResponse>>> listRefunds(
            @RequestParam String paymentId) {
        List<RefundResponse> refunds = refundService.listByPayment(paymentId);
        return ResponseEntity.ok(ApiResponse.success(refunds));
    }
}
